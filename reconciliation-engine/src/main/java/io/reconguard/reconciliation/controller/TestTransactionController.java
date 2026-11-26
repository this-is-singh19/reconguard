package io.reconguard.reconciliation.controller;

import io.reconguard.common.model.TransactionEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unauthenticated write endpoint into the raw topic. Dev-only: active under the {@code dev} profile,
 * never in a production profile (A3.14).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/test/transactions")
public class TestTransactionController {

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public TestTransactionController(KafkaTemplate<String, TransactionEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping
    public ResponseEntity<String> publishTransaction(@RequestBody TransactionEvent event) {

        // Same key as ingestion (tenant:order) so both legs of an order land on one partition (A3.4)
        String key = event.tenantId() + ":" + event.orderId();
        List<Header> headers = List.of(new RecordHeader("X-Tenant-Id", event.tenantId().getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(new ProducerRecord<>("reconguard.transactions.raw", null, key, event, headers));

        return ResponseEntity.ok("Event published to Kafka topic: reconguard.transactions.raw");
    }
}
