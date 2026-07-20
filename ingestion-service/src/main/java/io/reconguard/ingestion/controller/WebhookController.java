package io.reconguard.ingestion.controller;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.ingestion.producer.TransactionProducer;
import io.reconguard.ingestion.service.NormalizationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final NormalizationService normalizationService;
    private final TransactionProducer transactionProducer;

    // Clean constructor injection (Spring automatically wires these, no @Autowired needed)
    public WebhookController(NormalizationService normalizationService, TransactionProducer transactionProducer) {
        this.normalizationService = normalizationService;
        this.transactionProducer = transactionProducer;
    }

    /**
     * Accepts incoming financial transactions from external payment providers.
     * Uses @Valid to catch structural errors at the front gate.
     */
    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> ingestTransaction(@Valid @RequestBody TransactionEvent event) {
        log.info("Received transaction ingestion request: ID={}", event.transactionId());

        // 1. Clean data and bind client tenant variables
        TransactionEvent normalized = normalizationService.normalize(event);

        // 2. Stream safely to Kafka partition brokers
        transactionProducer.sendTransactionEvent(normalized);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "transactionId", normalized.transactionId(),
                "processedAt", Instant.now().toString()
        ));
    }
}