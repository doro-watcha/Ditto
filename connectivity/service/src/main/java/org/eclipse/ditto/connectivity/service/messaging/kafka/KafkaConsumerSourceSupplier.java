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
package org.eclipse.ditto.connectivity.service.messaging.kafka;


import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import akka.kafka.javadsl.Consumer;
import akka.stream.javadsl.Source;

/**
 * Supplier of a {@code Source<ConsumerRecord<String, String>, Consumer.Control>} used by {@code KafkaConsumerActor}
 * to consume messages from a kafka topic.
 */
@FunctionalInterface
public interface KafkaConsumerSourceSupplier
        extends Supplier<Source<ConsumerRecord<String, String>, Consumer.Control>> {
}
