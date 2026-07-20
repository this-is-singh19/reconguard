package io.reconguard.ingestion.producer;

import io.reconguard.common.model.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class TransactionProducer {

    private static final String TOPIC_NAME = "reconguard.transactions.raw";
    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public TransactionProducer(KafkaTemplate<String, TransactionEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a normalized transaction event to the Kafka cluster asynchronously.
     * Uses the Order ID as the message key to guarantee sequential ordering per order.
     */
    public void sendTransactionEvent(TransactionEvent event) {
        String messageKey = event.orderId();
        log.info("Publishing event to Kafka topic [{}]: Key (OrderId) = {}, TxId = {}",
                TOPIC_NAME, messageKey, event.transactionId());

        // Asynchronously publish the record
        CompletableFuture<SendResult<String, TransactionEvent>> future =
                kafkaTemplate.send(TOPIC_NAME, messageKey, event);

        // Handle the non-blocking callback log lines
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Successfully delivered message to Kafka topic [{}], partition [{}], offset [{}]",
                        TOPIC_NAME,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish transaction event to Kafka. TxId: {}. Reason: {}",
                        event.transactionId(), ex.getMessage(), ex);
                // In production, you would forward this failure to an internal Dead Letter Queue (DLQ)
            }
        });
    }
}