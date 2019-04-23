/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.base.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mutabilitydetector.unittesting.AllowedReason.provided;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;

import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link org.eclipse.ditto.services.base.config.DefaultClusterConfig}.
 */
public final class DefaultClusterConfigTest {

    private static Config clusterTestConf;

    @BeforeClass
    public static void initTestFixture() {
        clusterTestConf = ConfigFactory.load("cluster-test");
    }


    @Test
    public void assertImmutability() {
        assertInstancesOf(DefaultClusterConfig.class,
                areImmutable(),
                provided(Config.class).isAlsoImmutable());
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(DefaultClusterConfig.class)
                .usingGetClass()
                .verify();
    }

    @Test
    public void testSerializationAndDeserialization() throws IOException, ClassNotFoundException {
        final DefaultClusterConfig underTest = DefaultClusterConfig.of(clusterTestConf);

        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final ObjectOutput objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(underTest);
        objectOutputStream.close();

        final byte[] underTestSerialized = byteArrayOutputStream.toByteArray();

        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(underTestSerialized);
        final ObjectInput objectInputStream = new ObjectInputStream(byteArrayInputStream);
        final Object underTestDeserialized = objectInputStream.readObject();

        assertThat(underTestDeserialized).isEqualTo(underTest);
    }

    @Test
    public void underTestReturnsDefaultValuesIfBaseConfigWasEmpty() {
        final DefaultClusterConfig underTest = DefaultClusterConfig.of(ConfigFactory.empty());

        assertThat(underTest.getNumberOfShards())
                .as("getNumberOfShards")
                .isEqualTo(ServiceSpecificConfig.ClusterConfig.ClusterConfigValue.NUMBER_OF_SHARDS.getDefaultValue());
    }

    @Test
    public void underTestReturnsValuesOfConfigFile() {
        final DefaultClusterConfig underTest = DefaultClusterConfig.of(clusterTestConf);

        assertThat(underTest.getNumberOfShards())
                .as("getNumberOfShards")
                .isEqualTo(100);
    }

    @Test
    public void toStringReturnsExpected() {
        final DefaultClusterConfig underTest = DefaultClusterConfig.of(ConfigFactory.empty());

        assertThat(underTest.toString()).contains(underTest.getClass().getSimpleName()).contains("numberOfShards");
    }

}