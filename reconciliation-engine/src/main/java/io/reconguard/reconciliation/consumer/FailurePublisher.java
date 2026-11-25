package io.reconguard.reconciliation.consumer;

import io.reconguard.common.model.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FailurePublisher {

    private static final Logger log = LoggerFactory.getLogger(FailurePublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public FailurePublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishFailure(String tenantId, TransactionEvent event, String failureReason) {
        String topic = "recon.failures." + tenantId;

        // Wrap event with failure metadata since TransactionEvent is an immutable record
        FailurePayload payload = new FailurePayload(event, failureReason);

        kafkaTemplate.send(topic, event.transactionId(), payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Published failure to topic {} for transactionId: {}", topic, event.transactionId());
                    } else {
                        log.error("Failed to publish failure to topic {} for transactionId: {}", topic, event.transactionId(), ex);
                    }
                });
    }

    public record FailurePayload(
            TransactionEvent event,
            String failureReason
    ) {}
}