package io.reconguard.reconciliation.controller;

import io.reconguard.common.model.TransactionEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

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

        kafkaTemplate.send(
                "reconguard.transactions.raw",
                event.transactionId(),
                event
        );

        return ResponseEntity.ok("Event published to Kafka topic: reconguard.transactions.raw");
    }
}
