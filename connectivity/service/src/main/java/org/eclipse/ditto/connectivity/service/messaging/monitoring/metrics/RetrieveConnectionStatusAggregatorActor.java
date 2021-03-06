/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.connectivity.service.messaging.monitoring.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.ResourceStatus;
import org.eclipse.ditto.connectivity.model.SshTunnel;
import org.eclipse.ditto.connectivity.model.signals.commands.query.RetrieveConnectionStatusResponse;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.ReceiveTimeout;
import akka.event.DiagnosticLoggingAdapter;

/**
 * An aggregation actor which receives {@link ResourceStatus} messages from all {@code clients, targets and sources}
 * and aggregates them into a single {@link RetrieveConnectionStatusResponse} message it sends back to a passed in
 * {@code sender}.
 */
public final class RetrieveConnectionStatusAggregatorActor extends AbstractActor {

    private final DiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);

    private final Duration timeout;
    private final Map<ResourceStatus.ResourceType, Integer> expectedResponses;
    private final ActorRef sender;

    private final RetrieveConnectionStatusResponse.Builder responseBuilder;

    @SuppressWarnings("unused")
    private RetrieveConnectionStatusAggregatorActor(final Connection connection,
            final ActorRef sender, final DittoHeaders originalHeaders, final Duration timeout) {
        this.timeout = timeout;
        this.sender = sender;
        responseBuilder = RetrieveConnectionStatusResponse.getBuilder(connection.getId(), originalHeaders)
                .connectionStatus(connection.getConnectionStatus())
                .liveStatus(ConnectivityStatus.UNKNOWN)
                .connectedSince(Instant.EPOCH)
                .clientStatus(Collections.emptyList())
                .sourceStatus(Collections.emptyList())
                .targetStatus(Collections.emptyList())
                .sshTunnelStatus(Collections.emptyList());

        expectedResponses = new EnumMap<>(ResourceStatus.ResourceType.class);
        final int clientCount = connection.getClientCount();
        // one response per client actor
        expectedResponses.put(ResourceStatus.ResourceType.CLIENT, clientCount);
        if (ConnectivityStatus.OPEN.equals(connection.getConnectionStatus())) {
            // one response per source/target
            expectedResponses.put(ResourceStatus.ResourceType.TARGET,
                    connection.getTargets()
                            .stream()
                            .mapToInt(target -> clientCount)
                            .sum());
            expectedResponses.put(ResourceStatus.ResourceType.SOURCE,
                    connection.getSources()
                            .stream()
                            .mapToInt(source -> clientCount * source.getConsumerCount() * source.getAddresses().size())
                            .sum());
            if (connection.getSshTunnel().map(SshTunnel::isEnabled).orElse(false)) {
                expectedResponses.put(ResourceStatus.ResourceType.SSH_TUNNEL, clientCount);
            }
        }
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection the {@code Connection} for which to aggregate the status for.
     * @param sender the ActorRef of the sender to which to answer the response to.
     * @param originalHeaders the DittoHeaders to use for the response message.
     * @param timeout the timeout to apply in order to receive the response.
     * @return the Akka configuration Props object
     */
    public static Props props(final Connection connection, final ActorRef sender, final DittoHeaders originalHeaders,
            final Duration timeout) {
        return Props.create(RetrieveConnectionStatusAggregatorActor.class, connection, sender, originalHeaders,
                timeout);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ResourceStatus.class, this::handleResourceStatus)
                .match(ReceiveTimeout.class, receiveTimeout -> this.handleReceiveTimeout())
                .matchAny(any -> log.info("Cannot handle {}", any.getClass())).build();
    }

    private void handleReceiveTimeout() {
        final Map<ResourceStatus.ResourceType, Integer> missingResources = expectedResponses.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        log.warning("RetrieveConnectionStatus timed out, sending (partial) response with missing resources: <{}>",
                missingResources);
        responseBuilder.withMissingResources(missingResources);
        sendResponse();
        stopSelf();
    }

    @Override
    public void preStart() {
        getContext().setReceiveTimeout(timeout);
    }

    private void handleResourceStatus(final ResourceStatus resourceStatus) {
        expectedResponses.compute(resourceStatus.getResourceType(), (type, count) -> count == null ? 0 : count - 1);
        log.debug("Received resource status from {}: {}", getSender(), resourceStatus);
        // aggregate status...
        responseBuilder.withAddressStatus(resourceStatus);

        // if response is complete, send back to caller
        if (getRemainingResponses() == 0) {
            sendResponse();
        }
    }

    private int getRemainingResponses() {
        return expectedResponses.values().stream()
                .mapToInt(i -> i)
                .sum();
    }

    private void sendResponse() {
        final RetrieveConnectionStatusResponse tmpResponse = responseBuilder.build();
        final List<ResourceStatus> clientStatus = tmpResponse.getClientStatus();
        final ConnectivityStatus liveClientStatus = determineLiveStatus(clientStatus);
        final ConnectivityStatus liveSourceStatus = determineLiveStatus(tmpResponse.getSourceStatus());
        final ConnectivityStatus liveTargetStatus = determineLiveStatus(tmpResponse.getTargetStatus());
        final ConnectivityStatus liveSshTunnelStatus = determineLiveStatus(tmpResponse.getSshTunnelStatus());

        final Optional<Instant> earliestConnectedSince = clientStatus.stream()
                .filter(rs -> ConnectivityStatus.OPEN.equals(rs.getStatus()))
                .map(ResourceStatus::getInStateSince)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Instant::compareTo);

        final ConnectivityStatus liveStatus = calculateOverallLiveStatus(liveClientStatus, liveSourceStatus,
                liveTargetStatus, liveSshTunnelStatus);

        responseBuilder
                .connectedSince(earliestConnectedSince.orElse(null))
                .liveStatus(liveStatus);
        sender.tell(responseBuilder.build(), getSelf());
        stopSelf();
    }

    private static ConnectivityStatus determineLiveStatus(final List<ResourceStatus> resourceStatus) {
        final boolean allOpen = resourceStatus.stream()
                .map(ResourceStatus::getStatus)
                .allMatch(ConnectivityStatus.OPEN::equals);
        final boolean anyFailed = resourceStatus.stream()
                .map(ResourceStatus::getStatus)
                .anyMatch(ConnectivityStatus.FAILED::equals);
        final boolean anyMisconfigured = resourceStatus.stream()
                .map(ResourceStatus::getStatus)
                .anyMatch(ConnectivityStatus.MISCONFIGURED::equals);
        final boolean allClosed = resourceStatus.stream()
                .map(ResourceStatus::getStatus)
                .allMatch(ConnectivityStatus.CLOSED::equals);

        final ConnectivityStatus liveStatus;
        if (allOpen) {
            liveStatus = ConnectivityStatus.OPEN;
        } else if (anyFailed) {
            // hypothesis: even if any status is "misconfigured", the "failed" is stronger as the internal error
            // outweighs the misconfiguration
            liveStatus = ConnectivityStatus.FAILED;
        } else if (anyMisconfigured) {
            liveStatus = ConnectivityStatus.MISCONFIGURED;
        } else if (allClosed) {
            liveStatus = ConnectivityStatus.CLOSED;
        } else {
            liveStatus = ConnectivityStatus.UNKNOWN;
        }
        return liveStatus;
    }

    private static ConnectivityStatus calculateOverallLiveStatus(final ConnectivityStatus... liveStatuses) {
        final List<ConnectivityStatus> allStatuses = Arrays.asList(liveStatuses);
        if (allStatuses.stream().allMatch(ConnectivityStatus.OPEN::equals)) {
            return ConnectivityStatus.OPEN;
        }
        if (allStatuses.contains(ConnectivityStatus.FAILED)) {
            // hypothesis: even if other statuses are "misconfigured", the "failed" is stronger as the internal error
            // outweighs the misconfiguration
            return ConnectivityStatus.FAILED;
        }
        if (allStatuses.contains(ConnectivityStatus.MISCONFIGURED)) {
            return ConnectivityStatus.MISCONFIGURED;
        }
        if (allStatuses.stream().allMatch(ConnectivityStatus.CLOSED::equals)) {
            return ConnectivityStatus.CLOSED;
        }
        return ConnectivityStatus.UNKNOWN;
    }

    private void stopSelf() {
        getContext().stop(getSelf());
    }
}
