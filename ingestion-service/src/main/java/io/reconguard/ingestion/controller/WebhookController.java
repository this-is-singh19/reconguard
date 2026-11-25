package io.reconguard.ingestion.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_KEY_PREFIX = "recon:ingest:idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    /** Placeholder stored while the first request is still being processed. */
    private static final String IN_PROGRESS = "IN_PROGRESS";

    // Clean constructor injection (Spring automatically wires these, no @Autowired needed)
    public WebhookController(NormalizationService normalizationService,
                             TransactionProducer transactionProducer,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper) {
        this.normalizationService = normalizationService;
        this.transactionProducer = transactionProducer;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Accepts incoming financial transactions from external payment providers.
     * Uses @Valid to catch structural errors at the front gate.
     * A duplicate X-Idempotency-Key replays the original response body.
     */
    @PostMapping(value = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingestTransaction(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionEvent event) {

        String tenantId = TenantContext.getTenantId();
        String redisKey = IDEMPOTENCY_KEY_PREFIX + tenantId + ":" + idempotencyKey;

        // Atomic check-and-set via Redis SETNX
        Boolean isNewRequest = redisTemplate.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, IDEMPOTENCY_TTL);

        if (Boolean.FALSE.equals(isNewRequest)) {
            log.info("Duplicate ingestion request detected. Key: {}, Tenant: {}", idempotencyKey, tenantId);
            return duplicateResponse(redisKey, idempotencyKey);
        }

        log.info("Received fresh transaction ingestion request: ID={}, IdempotencyKey={}",
                event.transactionId(), idempotencyKey);

        // 1. Clean data and bind client tenant variables
        TransactionEvent normalized = normalizationService.normalize(event);

        // 2. Stream safely to Kafka partition brokers
        try {
            transactionProducer.sendTransactionEvent(normalized);
        } catch (RuntimeException ex) {
            // Nothing was published: release the key so the client can retry.
            redisTemplate.delete(redisKey);
            throw ex;
        }

        Map<String, Object> body = Map.of(
                "status", "ACCEPTED",
                "transactionId", normalized.transactionId(),
                "idempotencyKey", idempotencyKey,
                "duplicate", false,
                "processedAt", Instant.now().toString()
        );

        // 3. Cache the response so a duplicate request can replay it
        redisTemplate.opsForValue().set(redisKey, toJson(body), IDEMPOTENCY_TTL);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private ResponseEntity<Map<String, Object>> duplicateResponse(String redisKey, String idempotencyKey) {
        String cached = redisTemplate.opsForValue().get(redisKey);
        Map<String, Object> original = parseCached(cached);
        if (original != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(original);
        }
        // First request has not stored its response yet (still in progress).
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "idempotencyKey", idempotencyKey,
                "duplicate", true,
                "message", "Transaction ingestion already processed or in progress"
        ));
    }

    private Map<String, Object> parseCached(String cached) {
        if (cached == null || IN_PROGRESS.equals(cached)) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            log.warn("Could not parse cached ingestion response; treating as in progress");
            return null;
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize ingestion response", ex);
        }
    }
}
