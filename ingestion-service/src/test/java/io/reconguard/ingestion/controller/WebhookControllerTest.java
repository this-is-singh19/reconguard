package io.reconguard.ingestion.controller;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.ingestion.exception.GlobalExceptionHandler;
import io.reconguard.ingestion.producer.TransactionProducer;
import io.reconguard.ingestion.service.NormalizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@ContextConfiguration(classes = {WebhookController.class, GlobalExceptionHandler.class})
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NormalizationService normalizationService;

    @MockBean
    private TransactionProducer transactionProducer;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant-test");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Should return 400 Bad Request RFC 7807 when X-Idempotency-Key header is missing")
    void shouldReturn400WhenIdempotencyHeaderIsMissing() throws Exception {
        String jsonPayload = """
                {
                    "transactionId": "tx-100",
                    "tenantId": "tenant-test",
                    "orderId": "order-100",
                    "monetaryAmount": {
                        "value": 100.00,
                        "currency": "USD"
                    },
                    "type": "PAYMENT",
                    "timestamp": "2026-08-02T20:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Missing Required Header"))
                .andExpect(jsonPath("$.detail").value("Required header 'X-Idempotency-Key' is missing."));
    }

    @Test
    @DisplayName("Should accept fresh request, save idempotency key, and publish event")
    void shouldAcceptFreshRequestAndPublish() throws Exception {
        String idempotencyKey = "key-abc-123";
        String jsonPayload = """
                {
                    "transactionId": "tx-100",
                    "tenantId": "tenant-test",
                    "orderId": "order-100",
                    "monetaryAmount": {
                        "value": 100.00,
                        "currency": "USD"
                    },
                    "type": "PAYMENT",
                    "timestamp": "2026-08-02T20:00:00Z"
                }
                """;

        TransactionEvent rawEvent = new TransactionEvent(
                "tx-100", "tenant-test", "order-100",
                new MonetaryAmount(new BigDecimal("100.00"), "USD"),
                TransactionEventType.PAYMENT, Instant.parse("2026-08-02T20:00:00Z")
        );

        when(valueOperations.setIfAbsent(eq("recon:ingest:idempotency:tenant-test:" + idempotencyKey), eq("PROCESSED"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(normalizationService.normalize(any(TransactionEvent.class))).thenReturn(rawEvent);

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey));

        verify(transactionProducer, times(1)).sendTransactionEvent(any());
    }

    @Test
    @DisplayName("Should detect duplicate request, return cached ACCEPTED, and skip publishing")
    void shouldDetectDuplicateRequestAndSkipPublishing() throws Exception {
        String idempotencyKey = "key-abc-123";
        String jsonPayload = """
                {
                    "transactionId": "tx-100",
                    "tenantId": "tenant-test",
                    "orderId": "order-100",
                    "monetaryAmount": {
                        "value": 100.00,
                        "currency": "USD"
                    },
                    "type": "PAYMENT",
                    "timestamp": "2026-08-02T20:00:00Z"
                }
                """;

        // Redis returns false indicating key already exists
        when(valueOperations.setIfAbsent(eq("recon:ingest:idempotency:tenant-test:" + idempotencyKey), eq("PROCESSED"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey));

        verify(transactionProducer, never()).sendTransactionEvent(any());
    }
}