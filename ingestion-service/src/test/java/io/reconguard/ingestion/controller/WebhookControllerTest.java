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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@ContextConfiguration(classes = {WebhookController.class, GlobalExceptionHandler.class})
class WebhookControllerTest {

    private static final String REDIS_KEY = "recon:ingest:idempotency:tenant-test:key-abc-123";

    private static final String PAYLOAD = """
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NormalizationService normalizationService;

    @MockitoBean
    private TransactionProducer transactionProducer;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
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

    private TransactionEvent rawEvent() {
        return new TransactionEvent(
                "tx-100", "tenant-test", "order-100",
                new MonetaryAmount(new BigDecimal("100.00"), "USD"),
                TransactionEventType.PAYMENT, Instant.parse("2026-08-02T20:00:00Z")
        );
    }

    @Test
    @DisplayName("Should return 400 Bad Request RFC 7807 when X-Idempotency-Key header is missing")
    void shouldReturn400WhenIdempotencyHeaderIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Missing Required Header"))
                .andExpect(jsonPath("$.detail").value("Required header 'X-Idempotency-Key' is missing."));
    }

    @Test
    @DisplayName("Should accept fresh request, cache the response, and publish event")
    void shouldAcceptFreshRequestAndPublish() throws Exception {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(normalizationService.normalize(any(TransactionEvent.class))).thenReturn(rawEvent());

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", "key-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.transactionId").value("tx-100"))
                .andExpect(jsonPath("$.idempotencyKey").value("key-abc-123"));

        verify(transactionProducer, times(1)).sendTransactionEvent(any());
        verify(valueOperations).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Should replay the original cached response for a duplicate and skip publishing")
    void shouldReplayOriginalResponseForDuplicate() throws Exception {
        String cached = "{\"status\":\"ACCEPTED\",\"transactionId\":\"tx-100\","
                + "\"idempotencyKey\":\"key-abc-123\",\"duplicate\":false,"
                + "\"processedAt\":\"2026-08-02T20:00:01Z\"}";

        when(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY)).thenReturn(cached);

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", "key-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value("tx-100"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.processedAt").value("2026-08-02T20:00:01Z"));

        verify(transactionProducer, never()).sendTransactionEvent(any());
    }

    @Test
    @DisplayName("Should return in-progress ACCEPTED when the first request has not cached its response yet")
    void shouldReturnInProgressWhenNoCachedResponse() throws Exception {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY)).thenReturn("IN_PROGRESS");

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", "key-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.idempotencyKey").value("key-abc-123"));

        verify(transactionProducer, never()).sendTransactionEvent(any());
    }

    @Test
    @DisplayName("Should release the idempotency key when publishing fails so the client can retry")
    void shouldReleaseKeyWhenPublishFails() throws Exception {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(normalizationService.normalize(any(TransactionEvent.class))).thenReturn(rawEvent());
        doThrow(new IllegalStateException("kafka down"))
                .when(transactionProducer).sendTransactionEvent(any());

        try {
            mockMvc.perform(post("/api/v1/webhooks/transactions")
                    .header("X-Idempotency-Key", "key-abc-123")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYLOAD));
        } catch (Exception ignored) {
            // The unhandled publish failure may surface through MockMvc; the verification below is what matters.
        }

        verify(redisTemplate).delete(REDIS_KEY);
        verify(valueOperations, never()).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Should return 400 problem+json when request body is not valid JSON")
    void shouldReturn400WhenBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .header("X-Idempotency-Key", "key-malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\": \"tx-100\", "))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Malformed Request Body"));

        verify(transactionProducer, never()).sendTransactionEvent(any());
    }
}
