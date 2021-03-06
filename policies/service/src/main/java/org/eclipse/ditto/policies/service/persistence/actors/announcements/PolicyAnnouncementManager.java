/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.policies.service.persistence.actors.announcements;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.ditto.internal.utils.akka.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.pubsub.DistributedPub;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyEntry;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.PolicyLifecycle;
import org.eclipse.ditto.policies.model.Subject;
import org.eclipse.ditto.policies.model.SubjectId;
import org.eclipse.ditto.policies.model.Subjects;
import org.eclipse.ditto.policies.model.signals.announcements.PolicyAnnouncement;
import org.eclipse.ditto.policies.service.common.config.PolicyAnnouncementConfig;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.japi.pf.ReceiveBuilder;

/**
 * Manager of actors responsible for policy announcements.
 */
public final class PolicyAnnouncementManager extends AbstractActor {

    private final DittoDiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);

    private final PolicyId policyId;
    private final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub;
    private final Map<Subject, ActorRef> subjectExpiryActors;
    private final Map<ActorRef, Subject> activeSubjects;
    private final Map<SubjectId, Integer> activeSubjectIds;
    private final ActorRef commandForwarder;
    private final PolicyAnnouncementConfig config;

    @SuppressWarnings("unused")
    private PolicyAnnouncementManager(final PolicyId policyId,
            final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub,
            final ActorRef commandForwarder,
            final PolicyAnnouncementConfig config) {
        this.policyId = policyId;
        this.policyAnnouncementPub = policyAnnouncementPub;
        this.commandForwarder = commandForwarder;
        this.subjectExpiryActors = new HashMap<>();
        this.activeSubjects = new HashMap<>();
        this.activeSubjectIds = new HashMap<>();
        this.config = config;
    }

    /**
     * Create the Props object for this actor.
     *
     * @param policyId the policy ID.
     * @param policyAnnouncementPub Ditto pubsub API to publish policy announcements.
     * @param forwarder actor to forward policy commands to.
     * @param config config containing
     * @return The Props object.
     */
    public static Props props(final PolicyId policyId,
            final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub,
            final ActorRef forwarder,
            final PolicyAnnouncementConfig config) {
        return Props.create(PolicyAnnouncementManager.class, policyId, policyAnnouncementPub, forwarder, config);
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                // PolicyPersistenceActor sends the Policy when recovered and whenever the Policy is modified:
                .match(Policy.class, this::onPolicyModified)
                .match(Terminated.class, this::onChildTerminated)
                .build();
    }

    private void onPolicyModified(final Policy policy) {
        final var subjects = getSubjectsWithExpiryOrAnnouncements(policy);
        final var newSubjects = calculateDifference(subjects, subjectExpiryActors.keySet());
        final var deletedSubjects = calculateDifference(subjectExpiryActors.keySet(), subjects);
        log.debug("OnPolicyModified policy=<{}> newSubjects=<{}> deletedSubjects=<{}>", policy, newSubjects,
                deletedSubjects);
        for (final var newSubject : newSubjects) {
            startChild(newSubject);
        }
        for (final var deletedSubject : deletedSubjects) {
            // precondition: child actors have started for new subjects so that modified subjects can be recognized
            sendSubjectDeleted(deletedSubject);
        }
    }

    private void startChild(final Subject subject) {
        final var props =
                SubjectExpiryActor.props(policyId, subject, config.getGracePeriod(), policyAnnouncementPub,
                        config.getMaxTimeout(), commandForwarder, config);

        final var child = getContext().actorOf(props);
        getContext().watch(child);
        subjectExpiryActors.put(subject, child);
        activeSubjects.put(child, subject);
        addActiveSubjectId(subject);
    }

    private void onChildTerminated(final Terminated terminated) {
        final var terminatedActor = terminated.actor();
        final var removedSubject = activeSubjects.remove(terminatedActor);
        if (removedSubject != null) {
            final var child = subjectExpiryActors.remove(removedSubject);
            removeActiveSubjectId(removedSubject);
            log.debug("OnChildTerminated: Removed terminated child <{}>", child);
        } else {
            log.debug("OnChildTerminated: Child not found: <{}>", terminatedActor);
        }
    }

    private void sendSubjectDeleted(final Subject subject) {
        final var child = subjectExpiryActors.get(subject);
        if (child != null) {
            notifyChildOfSubjectDeletion(subject, child);
        } else {
            log.error("Attempting to notify nonexistent child for deleted subject <{}>", subject);
        }
    }

    /**
     * Notify a child whose subject was deleted from the policy.
     *
     * @param subject the subject.
     * @param child the recipient.
     */
    private void notifyChildOfSubjectDeletion(final Subject subject, final ActorRef child) {
        final var activeSubjectIdCount = activeSubjectIds.getOrDefault(subject.getId(), 0);
        if (activeSubjectIdCount >= 2) {
            // another actor took over the responsibility of child; terminate it.
            log.debug("Terminating child <{}> of updated subject <{}>", child, subject);
            child.tell(PoisonPill.getInstance(), ActorRef.noSender());
        } else {
            log.debug("Notifying child <{}> of deleted subject <{}>", child, subject);
            child.tell(SubjectExpiryActor.Message.SUBJECT_DELETED, ActorRef.noSender());
        }
    }

    private Set<Subject> getSubjectsWithExpiryOrAnnouncements(final Policy policy) {
        if (policy.getLifecycle().filter(lifeCycle -> lifeCycle == PolicyLifecycle.ACTIVE).isPresent()) {
            return StreamSupport.stream(policy.spliterator(), false)
                    .map(PolicyEntry::getSubjects)
                    .flatMap(Subjects::stream)
                    .filter(subject -> subject.getExpiry().isPresent() || subject.getAnnouncement().isPresent())
                    .collect(Collectors.toSet());
        } else {
            return Set.of();
        }
    }

    private void addActiveSubjectId(final Subject subject) {
        activeSubjectIds.compute(subject.getId(), (k, count) -> {
            if (count == null) {
                return 1;
            } else {
                return count + 1;
            }
        });
    }

    private void removeActiveSubjectId(final Subject subject) {
        activeSubjectIds.computeIfPresent(subject.getId(), (k, count) -> {
            if (count <= 1) {
                return null;
            } else {
                return count - 1;
            }
        });
    }

    private static List<Subject> calculateDifference(final Set<Subject> minuend, final Set<Subject> subtrahend) {
        return minuend.stream()
                .filter(subject -> !subtrahend.contains(subject))
                .collect(Collectors.toList());
    }
}
