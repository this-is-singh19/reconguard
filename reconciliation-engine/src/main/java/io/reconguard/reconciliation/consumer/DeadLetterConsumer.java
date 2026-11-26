package io.reconguard.reconciliation.consumer;

import io.reconguard.common.logging.TransactionMdc;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.reconciliation.config.KafkaConsumerConfig;
import io.reconguard.reconciliation.domain.DeadLetterEvent;
import io.reconguard.reconciliation.repository.DeadLetterEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
public class DeadLetterConsumer {

    private final DeadLetterEventRepository deadLetterEventRepository;

    public DeadLetterConsumer(DeadLetterEventRepository deadLetterEventRepository) {
        this.deadLetterEventRepository = deadLetterEventRepository;
    }

    @KafkaListener(
            topics = "${reconguard.topics.raw-transactions}.DLQ",
            groupId = "${reconguard.kafka.dlq-group-id}",
            containerFactory = KafkaConsumerConfig.TRANSACTION_EVENT_LISTENER_FACTORY
    )
    public void consumeDeadLetter(
            @Payload TransactionEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String dlqTopic,
            @Header(name = "kafka_dlt-original-topic", required = false) byte[] originalTopic,
            @Header(name = "kafka_dlt-exception-message", required = false) byte[] exceptionMessage
    ) {
        try (TransactionMdc ignored = TransactionMdc.open(
                event != null ? event.tenantId() : null,
                event != null ? event.orderId() : null,
                event != null ? event.transactionId() : null)) {
            handleDeadLetter(event, dlqTopic, originalTopic, exceptionMessage);
        }
    }

    private void handleDeadLetter(TransactionEvent event, String dlqTopic, byte[] originalTopic, byte[] exceptionMessage) {
        try {
            if (event != null && event.tenantId() != null) {
                TenantContext.setTenantId(event.tenantId());
            }
            String originalTopicName =
                    originalTopic != null ? new String(originalTopic) : "UNKNOWN";

            String failureReason =
                    exceptionMessage != null ? new String(exceptionMessage) : "UNKNOWN";
            log.error(
                    "ALERT [DLQ-RECEIVED]: Poison pill transaction trapped in Dead Letter Queue! " +
                            "TxId: {}, Tenant: {}, DLQ Topic: {}, Original Topic: {}, Failure Cause: {}",
                    event != null ? event.transactionId() : "UNKNOWN",
                    event != null ? event.tenantId() : "UNKNOWN",
                    dlqTopic,
                    originalTopicName,
                    failureReason
            );

            // A3.3: persist so operators can inspect and replay (G3.4). Failure here is logged, not retried.
            deadLetterEventRepository.save(DeadLetterEvent.builder()
                    .tenantId(event != null ? event.tenantId() : null)
                    .orderId(event != null ? event.orderId() : null)
                    .transactionId(event != null ? event.transactionId() : null)
                    .originalTopic(originalTopic != null ? new String(originalTopic) : null)
                    .dlqTopic(dlqTopic)
                    .failureReason(exceptionMessage != null ? new String(exceptionMessage) : null)
                    .receivedAt(Instant.now())
                    .build());

        } finally {
            TenantContext.clear();
        }
    }
}
