package io.reconguard.ai.config;

import io.reconguard.common.event.TransactionFailureEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer config for the failure topic. Typed to {@link TransactionFailureEvent} so it does not
 * depend on the engine's {@code TransactionEvent}-typed default container factory.
 */
@Configuration
public class FailureKafkaConsumerConfig {

    public static final String FAILURE_LISTENER_FACTORY = "failureKafkaListenerContainerFactory";
    public static final String FAILURE_DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${reconguard.ai.failure-consumer.group-id:ai-diagnostic-worker-group}")
    private String groupId;

    @Value("${reconguard.topics.failed-transactions:reconguard.transactions.failures}")
    private String failuresTopic;

    @Bean
    public ConsumerFactory<String, TransactionFailureEvent> failureConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Always bind to TransactionFailureEvent, ignoring producer-side __TypeId__ headers.
        JsonDeserializer<TransactionFailureEvent> jsonDeserializer =
                new JsonDeserializer<>(TransactionFailureEvent.class);
        jsonDeserializer.addTrustedPackages("io.reconguard.common.event");
        jsonDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(jsonDeserializer)
        );
    }

    @Bean(FAILURE_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, TransactionFailureEvent> failureKafkaListenerContainerFactory(
            ConsumerFactory<String, TransactionFailureEvent> failureConsumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, TransactionFailureEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(failureConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Poison/failed records go to <topic>.DLT on the same partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + FAILURE_DLT_SUFFIX, record.partition()));

        // 3 retries, 1s apart, then DLT
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
