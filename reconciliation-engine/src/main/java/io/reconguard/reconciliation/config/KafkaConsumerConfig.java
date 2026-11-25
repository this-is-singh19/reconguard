package io.reconguard.reconciliation.config;

import io.reconguard.common.model.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:reconciliation-service-group}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, TransactionEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Disable automatic commits to leverage Spring's manual transaction management
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Configure resilient error handling deserializers to protect against poison pills
        JsonDeserializer<TransactionEvent> jsonDeserializer = new JsonDeserializer<>(TransactionEvent.class);
        jsonDeserializer.addTrustedPackages("io.reconguard.*");

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(jsonDeserializer)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> kafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // Synchronize manual offset management to match our DB transaction lifecycle
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // 1. Recoverer: Formats DLQ target topic as 'recon.dlq.<tenantId>' (or falls back to '<topic>.DLQ')
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, ex) -> {
                            log.error("Retries exhausted for key: {} on topic: {}. Sending to DLQ.", record.key(), record.topic(), ex);

                            String tenantId = "default";
                            Header tenantHeader = record.headers().lastHeader("X-Tenant-Id");
                            if (tenantHeader != null && tenantHeader.value() != null) {
                                tenantId = new String(tenantHeader.value(), StandardCharsets.UTF_8);
                            }

                            String targetDlqTopic = "recon.dlq." + tenantId;
                            return new TopicPartition(targetDlqTopic, record.partition());
                        });

        // 2. Exponential Back-Off Error Handler (Initial: 1s, Multiplier: 2.0, Max Interval: 8s, Max Retries: 3)
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(8000L);
        backOff.setMaxElapsedTime(15000L); // Max total retry span before giving up to DLQ

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}