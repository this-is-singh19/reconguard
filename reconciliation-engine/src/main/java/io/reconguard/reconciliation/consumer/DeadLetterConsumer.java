package io.reconguard.reconciliation.consumer;

import io.reconguard.common.logging.TransactionMdc;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.reconciliation.config.KafkaConsumerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadLetterConsumer {

    @KafkaListener(
            topics = "reconguard.transactions.raw.DLQ",
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

            // TODO: Optional extension — Store to un-reconciled/dlq audit table in database for UI inspection

        } finally {
            TenantContext.clear();
        }
    }
}
