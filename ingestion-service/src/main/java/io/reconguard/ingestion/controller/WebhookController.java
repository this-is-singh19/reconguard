package io.reconguard.ingestion.controller;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.ingestion.producer.TransactionProducer;
import io.reconguard.ingestion.service.NormalizationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final NormalizationService normalizationService;
    private final TransactionProducer transactionProducer;
    private final StringRedisTemplate redisTemplate;

    private static final String IDEMPOTENCY_KEY_PREFIX = "recon:ingest:idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    // Clean constructor injection (Spring automatically wires these, no @Autowired needed)
    public WebhookController(NormalizationService normalizationService, TransactionProducer transactionProducer, StringRedisTemplate redisTemplate) {
        this.normalizationService = normalizationService;
        this.transactionProducer = transactionProducer;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Accepts incoming financial transactions from external payment providers.
     * Uses @Valid to catch structural errors at the front gate.
     */
    @PostMapping(value = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingestTransaction(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionEvent event) {

        String tenantId = TenantContext.getTenantId();
        String redisKey = IDEMPOTENCY_KEY_PREFIX + tenantId + ":" + idempotencyKey;

        // Atomic check-and-set via Redis SETNX
        Boolean isNewRequest = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSED", IDEMPOTENCY_TTL);

        if (Boolean.FALSE.equals(isNewRequest)) {
            log.info("Duplicate ingestion request detected. Key: {}, Tenant: {}", idempotencyKey, tenantId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "ACCEPTED",
                    "idempotencyKey", idempotencyKey,
                    "duplicate", true,
                    "message", "Transaction ingestion already processed or in progress"
            ));
        }

        log.info("Received fresh transaction ingestion request: ID={}, IdempotencyKey={}",
                event.transactionId(), idempotencyKey);

        // 1. Clean data and bind client tenant variables
        TransactionEvent normalized = normalizationService.normalize(event);

        // 2. Stream safely to Kafka partition brokers
        transactionProducer.sendTransactionEvent(normalized);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "transactionId", normalized.transactionId(),
                "idempotencyKey", idempotencyKey,
                "duplicate", false,
                "processedAt", Instant.now().toString()
        ));
    }
}