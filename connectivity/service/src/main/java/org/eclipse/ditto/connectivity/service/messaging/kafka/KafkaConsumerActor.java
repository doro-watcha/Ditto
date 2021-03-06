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

import static org.eclipse.ditto.connectivity.api.EnforcementFactoryFactory.newEnforcementFilterFactory;
import static org.eclipse.ditto.internal.models.placeholders.PlaceholderFactory.newHeadersPlaceholder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.signals.Signal;
import org.eclipse.ditto.connectivity.api.ExternalMessage;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.ConnectivityStatus;
import org.eclipse.ditto.connectivity.model.Enforcement;
import org.eclipse.ditto.connectivity.model.EnforcementFilterFactory;
import org.eclipse.ditto.connectivity.model.ResourceStatus;
import org.eclipse.ditto.connectivity.model.Source;
import org.eclipse.ditto.connectivity.service.messaging.AcknowledgeableMessage;
import org.eclipse.ditto.connectivity.service.messaging.BaseConsumerActor;
import org.eclipse.ditto.connectivity.service.messaging.ConnectivityStatusResolver;
import org.eclipse.ditto.connectivity.service.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.connectivity.service.messaging.internal.RetrieveAddressStatus;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.akka.logging.ThreadSafeDittoLoggingAdapter;
import org.eclipse.ditto.internal.utils.config.InstanceIdentifierSupplier;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.kafka.javadsl.Consumer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import scala.util.Either;

/**
 * Actor which streams messages from Kafka.
 */
final class KafkaConsumerActor extends BaseConsumerActor {

    private static final String TTL = "ttl";
    private static final String CREATION_TIME = "creation-time";
    static final String ACTOR_NAME_PREFIX = "kafkaConsumer-";

    private final ThreadSafeDittoLoggingAdapter log;
    private final KafkaConsumerStream kafkaStream;

    @SuppressWarnings("unused")
    private KafkaConsumerActor(final Connection connection,
            final KafkaConsumerSourceSupplier sourceSupplier,
            final String sourceAddress, final Sink<Object, NotUsed> inboundMappingSink,
            final Source source, final ConnectivityStatusResolver connectivityStatusResolver,
            final boolean dryRun) {
        super(connection, sourceAddress, inboundMappingSink, source, connectivityStatusResolver);

        log = DittoLoggerFactory.getThreadSafeDittoLoggingAdapter(this);

        final Enforcement enforcement = source.getEnforcement().orElse(null);
        final EnforcementFilterFactory<Map<String, String>, Signal<?>> headerEnforcementFilterFactory =
                enforcement != null
                        ? newEnforcementFilterFactory(enforcement, newHeadersPlaceholder())
                        : input -> null;
        final KafkaMessageTransformer kafkaMessageTransformer =
                new KafkaMessageTransformer(source, sourceAddress, headerEnforcementFilterFactory, inboundMonitor);
        kafkaStream = new KafkaConsumerStream(sourceSupplier, kafkaMessageTransformer, dryRun,
                Materializer.createMaterializer(this::getContext));
    }

    static Props props(final Connection connection,
            final KafkaConsumerSourceSupplier sourceSupplier,
            final String sourceAddress,
            final Sink<Object, NotUsed> inboundMappingSink,
            final Source source,
            final ConnectivityStatusResolver connectivityStatusResolver,
            final boolean dryRun) {
        return Props.create(KafkaConsumerActor.class, connection, sourceSupplier, sourceAddress,
                inboundMappingSink, source, connectivityStatusResolver, dryRun);
    }

    @Override
    public void preStart() throws IllegalStateException {
        kafkaStream.start();
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        shutdown();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ResourceStatus.class, this::handleAddressStatus)
                .match(RetrieveAddressStatus.class, ram -> getSender().tell(getCurrentSourceStatus(), getSelf()))
                .match(GracefulStop.class, stop -> this.shutdown())
                .matchAny(unhandled -> {
                    log.info("Unhandled message: {}", unhandled);
                    unhandled(unhandled);
                })
                .build();
    }

    @Override
    protected ThreadSafeDittoLoggingAdapter log() {
        return log;
    }

    private void shutdown() {
        if (kafkaStream != null) {
            kafkaStream.stop();
        }
    }

    /**
     * Message that allows gracefully stopping the consumer actor.
     */
    static final class GracefulStop {

        static final GracefulStop INSTANCE = new GracefulStop();

        private GracefulStop() {
            // intentionally empty
        }

    }

    private final class KafkaConsumerStream {

        private final akka.stream.javadsl.Source<Either<ExternalMessage, DittoRuntimeException>, Consumer.Control>
                runnableKafkaStream;
        private final Materializer materializer;
        @Nullable private Consumer.Control consumerControl;

        private KafkaConsumerStream(
                final KafkaConsumerSourceSupplier sourceSupplier,
                final KafkaMessageTransformer kafkaMessageTransformer,
                final boolean dryRun,
                final Materializer materializer) {

            this.materializer = materializer;
            runnableKafkaStream = sourceSupplier.get()
                    .filter(consumerRecord -> isNotDryRun(consumerRecord, dryRun))
                    .filter(consumerRecord -> consumerRecord.value() != null)
                    .filter(this::isNotExpired)
                    .map(kafkaMessageTransformer::transform)
                    .divertTo(this.externalMessageSink(), this::isExternalMessage)
                    .divertTo(this.dittoRuntimeExceptionSink(), this::isDittoRuntimeException);
        }

        private Sink<Either<ExternalMessage, DittoRuntimeException>, ?> externalMessageSink() {
            return Flow.fromFunction(this::extractExternalMessage)
                    .map(externalMessage -> AcknowledgeableMessage.of(externalMessage, () -> {
                                // TODO: kafka source - Implement acks
                            },
                            redeliver -> {
                                // TODO: kafka source - Implement acks
                            }))
                    .to(getMessageMappingSink());
        }

        private boolean isExternalMessage(final Either<ExternalMessage, DittoRuntimeException> value) {
            return value.isLeft();
        }

        private ExternalMessage extractExternalMessage(final Either<ExternalMessage, DittoRuntimeException> value) {
            return value.left().get();
        }

        private boolean isNotExpired(final ConsumerRecord<String, String> consumerRecord) {
            final Headers headers = consumerRecord.headers();
            final long now = Instant.now().toEpochMilli();
            try {
                final Optional<Long> creationTimeOptional = Optional.ofNullable(headers.lastHeader(CREATION_TIME))
                        .map(Header::value)
                        .map(String::new)
                        .map(Long::parseLong);
                final Optional<Long> ttlOptional = Optional.ofNullable(headers.lastHeader(TTL))
                        .map(Header::value)
                        .map(String::new)
                        .map(Long::parseLong);
                if (creationTimeOptional.isPresent() && ttlOptional.isPresent()) {
                    return now - creationTimeOptional.get() >= ttlOptional.get();
                }
                return true;
            } catch (final Exception e) {
                // Errors during reading/parsing headers should not cause the message to be dropped.
                return true;
            }
        }

        private boolean isNotDryRun(final ConsumerRecord<String, String> record, final boolean dryRun) {
            if (dryRun && log.isDebugEnabled()) {
                log.debug("Dropping record (key: {}, topic: {}, partition: {}, offset: {}) in dry run mode.",
                        record.key(), record.topic(), record.partition(), record.offset());
            }
            return !dryRun;
        }

        private Sink<Either<ExternalMessage, DittoRuntimeException>, ?> dittoRuntimeExceptionSink() {
            return Flow.fromFunction(this::extractDittoRuntimeException)
                    .to(getDittoRuntimeExceptionSink());
        }

        private boolean isDittoRuntimeException(final Either<ExternalMessage, DittoRuntimeException> value) {
            return value.isRight();
        }

        private DittoRuntimeException extractDittoRuntimeException(
                final Either<ExternalMessage, DittoRuntimeException> value) {
            return value.right().get();
        }

        private Sink<Either<ExternalMessage, DittoRuntimeException>, CompletionStage<Done>> unexpectedMessageSink() {
            return Sink.foreach(either -> inboundMonitor.exception(
                    "Got unexpected transformation result <{0}>. This is an internal error. " +
                            "Please contact the service team.", either
            ));
        }

        private void start() throws IllegalStateException {
            if (consumerControl != null) {
                stop();
            }
            runnableKafkaStream
                    .mapMaterializedValue(cc -> {
                        consumerControl = cc;
                        return cc;
                    })
                    .runWith(unexpectedMessageSink(), materializer)
                    .whenComplete(this::handleStreamCompletion);
        }

        private void handleStreamCompletion(@Nullable final Done done, @Nullable final Throwable throwable) {
            final ConnectivityStatus status;
            final ResourceStatus statusUpdate;
            final Instant now = Instant.now();
            if (null == throwable) {
                status = ConnectivityStatus.CLOSED;
                statusUpdate = ConnectivityModelFactory.newStatusUpdate(
                        InstanceIdentifierSupplier.getInstance().get(),
                        status,
                        sourceAddress,
                        "Consumer closed", now);
            } else {
                log.debug("Consumer failed with error! <{}: {}>", throwable.getClass().getSimpleName(),
                        throwable.getMessage());
                status = connectivityStatusResolver.resolve(throwable);
                escalate(throwable, "Unexpected consumer failure.");
                statusUpdate = ConnectivityModelFactory.newStatusUpdate(
                        InstanceIdentifierSupplier.getInstance().get(),
                        status,
                        sourceAddress,
                        ConnectionFailure.determineFailureDescription(now, throwable,
                                "Kafka consumer failed."), now);
            }
            handleAddressStatus(statusUpdate);
        }

        private void escalate(final Throwable throwable, final String description) {
            final ActorRef self = getContext().getSelf();
            getContext().getParent()
                    .tell(ConnectionFailure.of(self, throwable, description), self);
        }

        private void stop() {
            if (consumerControl != null) {
                consumerControl.drainAndShutdown(new CompletableFuture<>(), materializer.executionContext());
                consumerControl = null;
            }
        }

    }

}
