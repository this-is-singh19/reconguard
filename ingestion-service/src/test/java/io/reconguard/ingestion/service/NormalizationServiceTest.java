package io.reconguard.ingestion.service;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.ingestion.dto.StripeWebhookPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

class NormalizationServiceTest {

    private NormalizationService normalizationService;

    @BeforeEach
    void setUp() {
        normalizationService = new NormalizationService();
        TenantContext.setTenantId("tenant-acme");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Should normalize generic TransactionEvent values correctly")
    void shouldNormalizeGenericEvent() {
        TransactionEvent raw = new TransactionEvent(
                " tx-99 ",
                "temp-tenant",
                " order-88 ",
                new MonetaryAmount(new BigDecimal("150.5"), " usd "),
                TransactionEventType.ORDER,
                Instant.parse("2026-08-02T10:00:00Z")
        );

        TransactionEvent normalized = normalizationService.normalize(raw);

        assertEquals("tx-99", normalized.transactionId());
        assertEquals("tenant-acme", normalized.tenantId()); // Injected from TenantContext
        assertEquals("order-88", normalized.orderId());
        assertEquals(new BigDecimal("150.5000"), normalized.monetaryAmount().value());
        assertEquals("USD", normalized.monetaryAmount().currency());
    }

    @Test
    @DisplayName("Should normalize Stripe webhook payload with cents conversion")
    void shouldNormalizeStripePayload() {
        StripeWebhookPayload.StripeObject object = new StripeWebhookPayload.StripeObject(
                "pi_3MtwB2LkdIwHu7ix28a3tC",
                5000L, // $50.00 in cents
                "usd",
                1785667200L,
                Map.of("order_id", "ORD-1234")
        );
        StripeWebhookPayload stripePayload = new StripeWebhookPayload(
                "evt_123",
                "payment_intent.succeeded",
                new StripeWebhookPayload.StripeData(object)
        );

        TransactionEvent normalized = normalizationService.normalize(stripePayload);

        assertEquals("pi_3MtwB2LkdIwHu7ix28a3tC", normalized.transactionId());
        assertEquals("tenant-acme", normalized.tenantId());
        assertEquals("ORD-1234", normalized.orderId());
        assertEquals(new BigDecimal("50.0000"), normalized.monetaryAmount().value());
        assertEquals("USD", normalized.monetaryAmount().currency());
        assertEquals(TransactionEventType.PAYMENT, normalized.type());
    }
}