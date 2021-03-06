/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.concierge.service.common;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.internal.utils.cacheloaders.config.AskWithRetryConfig;
import org.eclipse.ditto.internal.utils.config.KnownConfigValue;

/**
 * Provides configuration settings for Concierge enforcement behaviour.
 */
@Immutable
public interface EnforcementConfig {

    /**
     * Returns the configuration for the used "ask with retry" pattern in the concierge enforcement to load
     * things+policies.
     *
     * @return the "ask with retry" pattern config for retrieval of things and policies.
     */
    AskWithRetryConfig getAskWithRetryConfig();

    /**
     * Returns the buffer size used for the queue in the enforcer actor.
     *
     * @return the buffer size.
     */
    int getBufferSize();

    /**
     * Returns whether live responses from channels other than their subscribers should be dispatched.
     *
     * @return whether global live response dispatching is enabled.
     */
    boolean shouldDispatchLiveResponsesGlobally();

    /**
     * An enumeration of the known config path expressions and their associated default values for
     * {@code EnforcementConfig}.
     */
    enum EnforcementConfigValue implements KnownConfigValue {

        /**
         * The buffer size used for the queue in the enforcer actor.
         */
        BUFFER_SIZE("buffer-size", 1_000),

        /**
         * Whether to enable dispatching live responses from channels other than the subscribers.
         */
        GLOBAL_LIVE_RESPONSE_DISPATCHING("global-live-response-dispatching", false);

        private final String path;
        private final Object defaultValue;

        EnforcementConfigValue(final String thePath, final Object theDefaultValue) {
            path = thePath;
            defaultValue = theDefaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

    }

}
