package io.reconguard.reconciliation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reconguard.common.model.TransactionEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/test/transactions")
public class TestTransactionController {

    //private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
/*
    public TestTransactionController(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }
*/
    public TestTransactionController(KafkaTemplate<String, TransactionEvent> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
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
    /*
    @PostMapping
    public ResponseEntity<String> publishTransaction(@RequestBody TransactionEvent event) throws Exception {
        String jsonPayload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send("reconguard.transactions.raw", event.transactionId(), event.tra);
        return ResponseEntity.ok("Event published to Kafka topic: reconguard.transactions.raw");
    }
    */
}