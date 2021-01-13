/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.policies.persistence.actors.strategies.commands;

import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.policies.EffectedPermissions;
import org.eclipse.ditto.model.policies.PoliciesResourceType;
import org.eclipse.ditto.model.policies.PolicyEntry;
import org.eclipse.ditto.services.models.policies.Permission;
import org.eclipse.ditto.services.policies.common.config.PolicyConfig;
import org.eclipse.ditto.services.policies.persistence.actors.resolvers.SubjectIdFromActionResolver;
import org.eclipse.ditto.services.utils.akka.AkkaClassLoader;
import org.eclipse.ditto.signals.commands.policies.actions.PolicyActionCommand;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyActionFailedException;

import akka.actor.ActorSystem;

/**
 * Abstract base class for {@link PolicyActionCommand} strategies.
 *
 * @param <C> the type of the handled command
 */
abstract class AbstractPolicyActionCommandStrategy<C extends PolicyActionCommand<C>>
        extends AbstractPolicyCommandStrategy<C> {

    protected final SubjectIdFromActionResolver subjectIdFromActionResolver;

    AbstractPolicyActionCommandStrategy(final Class<C> theMatchingClass,
            final PolicyConfig policyConfig,
            final ActorSystem system) {
        super(theMatchingClass, policyConfig);
        this.subjectIdFromActionResolver = AkkaClassLoader.instantiate(system, SubjectIdFromActionResolver.class,
                policyConfig.getSubjectIdResolver());
    }

    /**
     * Check whether a policy entry contains a READ permission for things.
     *
     * @param policyEntry the policy entry to check.
     * @return whether the entry contains a READ permission for things.
     */
    boolean containsThingReadPermission(final PolicyEntry policyEntry) {
        return policyEntry.getResources()
                .stream()
                .anyMatch(resource -> {
                    final String resourceType = resource.getResourceKey().getResourceType();
                    final EffectedPermissions permissions = resource.getEffectedPermissions();
                    return PoliciesResourceType.THING.equals(resourceType) &&
                            permissions.getGrantedPermissions().contains(Permission.READ) &&
                            !permissions.getRevokedPermissions().contains(Permission.READ);
                });
    }

    PolicyActionFailedException getExceptionForNoEntryWithThingReadPermission() {
        return PolicyActionFailedException.newBuilderForActivateTokenIntegration()
                .status(HttpStatusCode.NOT_FOUND)
                .description("No policy entry found with READ permission for things.")
                .build();
    }

}
