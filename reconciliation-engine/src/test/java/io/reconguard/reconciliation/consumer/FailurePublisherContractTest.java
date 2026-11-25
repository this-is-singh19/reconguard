package io.reconguard.reconciliation.consumer;

import io.reconguard.common.event.TransactionFailureEvent;
import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A2.1: engine publishes a flat TransactionFailureEvent to the single failures topic, keyed tenantId:orderId. */
class FailurePublisherContractTest {

    @Test
    @DisplayName("Publishes TransactionFailureEvent to reconguard.transactions.failures keyed tenant:order and round-trips for the AI worker")
    @SuppressWarnings("unchecked")
    void publishesFailureContract() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));
        FailurePublisher publisher = new FailurePublisher(template, "reconguard.transactions.failures");

        Instant ts = Instant.parse("2026-01-01T10:00:00Z");
        TransactionEvent event = new TransactionEvent("tx-1", "tenant-a", "order-7",
                new MonetaryAmount(new BigDecimal("12.5000"), "USD"), TransactionEventType.PAYMENT, ts);

        publisher.publishFailure("tenant-a", event, "Unmatched window expiration");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).send(eq("reconguard.transactions.failures"), eq("tenant-a:order-7"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(TransactionFailureEvent.class);

        // Wire round-trip using the AI worker's deserialization settings (type headers ignored, trusted package).
        byte[] bytes;
        try (JsonSerializer<Object> serializer = new JsonSerializer<>()) {
            bytes = serializer.serialize("reconguard.transactions.failures", payload.getValue());
        }
        try (JsonDeserializer<TransactionFailureEvent> deserializer = new JsonDeserializer<>(TransactionFailureEvent.class)) {
            deserializer.addTrustedPackages("io.reconguard.common.event");
            deserializer.setUseTypeHeaders(false);
            TransactionFailureEvent received = deserializer.deserialize("reconguard.transactions.failures", bytes);
            assertThat(received.transactionId()).isEqualTo("tx-1");
            assertThat(received.tenantId()).isEqualTo("tenant-a");
            assertThat(received.orderId()).isEqualTo("order-7");
            assertThat(received.amount()).isEqualByComparingTo("12.5");
            assertThat(received.currency()).isEqualTo("USD");
            assertThat(received.status()).isEqualTo("FAILED");
            assertThat(received.failureReason()).isEqualTo("Unmatched window expiration");
            assertThat(received.eventTimestamp()).isEqualTo(ts);
        }
    }
}
