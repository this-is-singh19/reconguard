package io.reconguard.reconciliation.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the engine's topics so they exist with explicit partition counts before the first send (A3.3, A3.15).
 * Names come from {@code reconguard.topics.*}; the DLQ name is derived from the raw topic ({@code <raw>.DLQ}).
 * Partition count is one key per order: same-order legs always land on the same partition (A3.4).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic rawTransactionsTopic(
            @Value("${reconguard.topics.raw-transactions}") String rawTopic,
            @Value("${reconguard.kafka.partitions:6}") int partitions) {
        return new NewTopic(rawTopic, partitions, (short) 1);
    }

    @Bean
    public NewTopic rawTransactionsDlqTopic(
            @Value("${reconguard.topics.raw-transactions}.DLQ") String dlqTopic,
            @Value("${reconguard.kafka.partitions:6}") int partitions) {
        return new NewTopic(dlqTopic, partitions, (short) 1);
    }

    @Bean
    public NewTopic failedTransactionsTopic(
            @Value("${reconguard.topics.failed-transactions}") String failuresTopic,
            @Value("${reconguard.kafka.partitions:6}") int partitions) {
        return new NewTopic(failuresTopic, partitions, (short) 1);
    }
}
