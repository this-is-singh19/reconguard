package io.reconguard.ingestion.producer;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.ingestion.exception.EventPublishException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class TransactionProducer {

    private static final String TOPIC_NAME = "reconguard.transactions.raw";
    static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * Longer than the producer's delivery.timeout.ms (application.yml), so the client has either an ack or a
     * definitive failure before we give up. Giving up earlier could release the idempotency key while the record
     * is still in flight, and the retry would then publish a duplicate.
     */
    private static final Duration PUBLISH_WAIT = Duration.ofSeconds(20);

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public TransactionProducer(KafkaTemplate<String, TransactionEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a normalized event and waits for the broker's ack (A4.4). Throws {@link EventPublishException} if the
     * broker does not acknowledge, so the caller can release the idempotency key and answer 503.
     * Key is {@code tenantId:orderId} (A3.4, A4.8); the tenant also travels in the {@code X-Tenant-Id} header.
     */
    public void sendTransactionEvent(TransactionEvent event) {
        String messageKey = event.tenantId() + ":" + event.orderId();
        List<Header> headers = List.of(
                new RecordHeader(TENANT_HEADER, event.tenantId().getBytes(StandardCharsets.UTF_8)));

        try {
            SendResult<String, TransactionEvent> result = kafkaTemplate
                    .send(new ProducerRecord<>(TOPIC_NAME, null, messageKey, event, headers))
                    .get(PUBLISH_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            log.info("Delivered to Kafka topic [{}], partition [{}], offset [{}], TxId: {}",
                    TOPIC_NAME, result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(), event.transactionId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while publishing transaction " + event.transactionId(), e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("Failed to publish transaction event to Kafka. TxId: {}", event.transactionId(), e);
            throw new EventPublishException("Broker did not acknowledge transaction " + event.transactionId(), e);
        }
    }
}
