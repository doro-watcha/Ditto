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

import java.util.Objects;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.internal.utils.cacheloaders.config.AskWithRetryConfig;
import org.eclipse.ditto.internal.utils.cacheloaders.config.DefaultAskWithRetryConfig;
import org.eclipse.ditto.internal.utils.config.ConfigWithFallback;

import com.typesafe.config.Config;

/**
 * This class implements {@link EnforcementConfig} for Ditto's Concierge service.
 */
@Immutable
public final class DefaultEnforcementConfig implements EnforcementConfig {

    private static final String CONFIG_PATH = "enforcement";
    private static final String ASK_WITH_RETRY_CONFIG_PATH = "ask-with-retry";

    private final AskWithRetryConfig askWithRetryConfig;
    private final int bufferSize;
    private final boolean globalLiveResponseDispatching;

    private DefaultEnforcementConfig(final ConfigWithFallback configWithFallback) {
        askWithRetryConfig = DefaultAskWithRetryConfig.of(configWithFallback, ASK_WITH_RETRY_CONFIG_PATH);
        bufferSize = configWithFallback.getPositiveIntOrThrow(EnforcementConfigValue.BUFFER_SIZE);
        globalLiveResponseDispatching =
                configWithFallback.getBoolean(EnforcementConfigValue.GLOBAL_LIVE_RESPONSE_DISPATCHING.getConfigPath());
    }

    /**
     * Returns an instance of {@code DefaultEnforcementConfig} based on the settings of the specified Config.
     *
     * @param config is supposed to provide the settings of the enforcement config at {@value #CONFIG_PATH}.
     * @return the instance.
     * @throws org.eclipse.ditto.internal.utils.config.DittoConfigError if {@code config} is invalid.
     */
    public static DefaultEnforcementConfig of(final Config config) {
        return new DefaultEnforcementConfig(
                ConfigWithFallback.newInstance(config, CONFIG_PATH, EnforcementConfigValue.values()));
    }

    @Override
    public AskWithRetryConfig getAskWithRetryConfig() {
        return askWithRetryConfig;
    }

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public boolean shouldDispatchLiveResponsesGlobally() {
        return globalLiveResponseDispatching;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultEnforcementConfig that = (DefaultEnforcementConfig) o;
        return bufferSize == that.bufferSize &&
                globalLiveResponseDispatching == that.globalLiveResponseDispatching &&
                askWithRetryConfig.equals(that.askWithRetryConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(askWithRetryConfig, bufferSize, globalLiveResponseDispatching);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "askWithRetryConfig=" + askWithRetryConfig +
                ", bufferSize=" + bufferSize +
                ", globalLiveResponseDispatching=" + globalLiveResponseDispatching +
                "]";
    }
}
