package io.reconguard.reconciliation.consumer;

import io.reconguard.common.event.TransactionFailureEvent;
import io.reconguard.common.model.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class FailurePublisher {

    private static final Logger log = LoggerFactory.getLogger(FailurePublisher.class);
    private static final String STATUS_FAILED = "FAILED";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String failuresTopic;

    public FailurePublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${reconguard.topics.failed-transactions:reconguard.transactions.failures}") String failuresTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.failuresTopic = failuresTopic;
    }

    /**
     * Publishes a {@link TransactionFailureEvent} (the contract consumed by the AI worker).
     * Key is {@code tenantId:orderId}; tenant is also carried in the {@code X-Tenant-Id} header.
     */
    public void publishFailure(String tenantId, TransactionEvent event, String failureReason) {
        TransactionFailureEvent payload = new TransactionFailureEvent(
                event.transactionId(),
                tenantId,
                event.orderId(),
                event.monetaryAmount() != null ? event.monetaryAmount().value() : null,
                event.monetaryAmount() != null ? event.monetaryAmount().currency() : null,
                STATUS_FAILED,
                failureReason,
                event.timestamp(),
                event.gateway(),
                event.errorCode(),
                event.region()
        );

        String key = tenantId + ":" + event.orderId();

        kafkaTemplate.send(failuresTopic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Published failure to topic {} for transactionId: {}", failuresTopic, event.transactionId());
                    } else {
                        log.error("Failed to publish failure to topic {} for transactionId: {}", failuresTopic, event.transactionId(), ex);
                    }
                });
    }
}
