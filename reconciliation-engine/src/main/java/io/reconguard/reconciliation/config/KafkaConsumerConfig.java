package io.reconguard.reconciliation.config;

import io.reconguard.common.model.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer wiring for the engine.
 * <ul>
 *   <li>Connection and group settings come from {@code spring.kafka.*} in application.yml.</li>
 *   <li>Deserialization (JSON type, trusted packages) is owned here, not in yml.</li>
 *   <li>One named listener factory for {@link TransactionEvent} payloads. Listeners reference it by name.</li>
 * </ul>
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    public static final String TRANSACTION_EVENT_LISTENER_FACTORY = "transactionEventKafkaListenerContainerFactory";

    /** Dead-letter topic for the raw transactions topic. The DLQ listener subscribes to this name. */
    static final String DLQ_SUFFIX = ".DLQ";

    /** Retries after the first delivery attempt (so 4 deliveries in total). */
    static final int MAX_RETRIES = 3;

    private static final String TRUSTED_PACKAGE = "io.reconguard.common.model";

    @Bean
    public ConsumerFactory<String, TransactionEvent> transactionEventConsumerFactory(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildConsumerProperties(null);

        // Offsets are committed by the listener container (AckMode.RECORD), not by the client
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<TransactionEvent> jsonDeserializer = new JsonDeserializer<>(TransactionEvent.class);
        jsonDeserializer.addTrustedPackages(TRUSTED_PACKAGE);

        // ErrorHandlingDeserializer keeps poison pills from crashing the poll loop
        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(jsonDeserializer)
        );
    }

    @Bean(TRANSACTION_EVENT_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> transactionEventKafkaListenerContainerFactory(
            ConsumerFactory<String, TransactionEvent> transactionEventConsumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionEventConsumerFactory);

        // Offsets are committed per record, only after the listener returns without throwing
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Recoverer: publishes the exhausted record to '<source topic>.DLQ' on its own partition choice (-1)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    log.error("Retries exhausted for key: {} on topic: {}. Sending to DLQ.",
                            record.key(), record.topic(), ex);
                    return new TopicPartition(record.topic() + DLQ_SUFFIX, -1);
                });

        // Exponential back-off 1s, 2s, 4s (capped at 8s), then MAX_RETRIES retries before the recoverer runs
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(8000L);
        backOff.setMaxAttempts(MAX_RETRIES);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // A3.1: programming/data errors will never succeed on retry; send them to the DLQ immediately.
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class, NullPointerException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
