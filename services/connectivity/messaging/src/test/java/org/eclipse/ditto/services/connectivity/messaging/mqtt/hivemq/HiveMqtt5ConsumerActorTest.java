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
package org.eclipse.ditto.services.connectivity.messaging.mqtt.hivemq;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.common.DittoConstants;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.PayloadMapping;
import org.eclipse.ditto.services.connectivity.messaging.AbstractConsumerActorTest;
import org.eclipse.ditto.services.connectivity.messaging.TestConstants;

import com.hivemq.client.internal.checkpoint.Confirmable;
import com.hivemq.client.internal.mqtt.message.publish.MqttPublish;
import com.hivemq.client.mqtt.mqtt5.datatypes.Mqtt5UserProperties;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import akka.actor.ActorRef;
import akka.actor.Props;

/**
 * Unit test for {@link HiveMqtt5ConsumerActor}.
 */
public final class HiveMqtt5ConsumerActorTest extends AbstractConsumerActorTest<Mqtt5Publish> {

    private final CountDownLatch confirmLatch = new CountDownLatch(1);

    private static final ConnectionId CONNECTION_ID = TestConstants.createRandomConnectionId();

    @Override
    protected Props getConsumerActorProps(final ActorRef mappingActor,
            final Set<AcknowledgementLabel> acknowledgements) {
        return HiveMqtt5ConsumerActor.props(CONNECTION_ID, mappingActor, ConnectivityModelFactory.newSourceBuilder()
                .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                .enforcement(ENFORCEMENT)
                .headerMapping(TestConstants.HEADER_MAPPING)
                .acknowledgements(acknowledgements)
                .build(), false);
    }

    @Override
    protected Props getConsumerActorProps(final ActorRef mappingActor, final PayloadMapping payloadMapping) {
        return HiveMqtt5ConsumerActor.props(CONNECTION_ID, mappingActor, ConnectivityModelFactory.newSourceBuilder()
                .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                .enforcement(ENFORCEMENT)
                .headerMapping(TestConstants.HEADER_MAPPING)
                .payloadMapping(payloadMapping)
                .build(), false);
    }

    @Override
    protected Mqtt5Publish getInboundMessage(final Map.Entry<String, Object> header) {
        final Mqtt5Publish mqtt5Publish = Mqtt5Publish.builder()
                .topic("org.eclipse.ditto.test/testThing/things/twin/commands/modify")
                .payload(TestConstants.modifyThing().getBytes(StandardCharsets.UTF_8))
                .contentType(DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE)
                .userProperties(Mqtt5UserProperties.builder()
                        .add(header.getKey(), header.getValue().toString())
                        .build())
                .responseTopic(REPLY_TO_HEADER.getValue())
                .build();
        final MqttPublish mqttPublish = (MqttPublish) mqtt5Publish;
        return mqttPublish.withConfirmable(new MockConfirmable());
    }

    @Override
    protected Mqtt5Publish getInboundMessage(final Map.Entry<String, Object> header,
            final Map.Entry<String, Object> header2) {
        final Mqtt5Publish mqtt5Publish = Mqtt5Publish.builder()
                .topic("org.eclipse.ditto.test/testThing/things/twin/commands/modify")
                .payload(TestConstants.modifyThing().getBytes(StandardCharsets.UTF_8))
                .contentType(DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE)
                .userProperties(Mqtt5UserProperties.builder()
                        .add(header.getKey(), header.getValue().toString())
                        .add(header2.getKey(), header2.getValue().toString())
                        .build())
                .responseTopic(REPLY_TO_HEADER.getValue())
                .build();
        final MqttPublish mqttPublish = (MqttPublish) mqtt5Publish;
        return mqttPublish.withConfirmable(new MockConfirmable());
    }

    @Override
    protected void verifyMessageSettlement(final boolean isSuccessExpected) throws Exception {
        if (isSuccessExpected) {
            assertThat(confirmLatch.await(3L, TimeUnit.SECONDS))
                    .describedAs("Expect MQTT5 confirmation")
                    .isTrue();
        }
        // TODO: verify negative ack if supported.
    }

    private final class MockConfirmable implements Confirmable {

        @Override
        public long getId() {
            return 1234L;
        }

        @Override
        public boolean confirm() {
            confirmLatch.countDown();
            return true;
        }
    }
}
