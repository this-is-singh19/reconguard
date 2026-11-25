package io.reconguard.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.reconguard.common.model.TransactionEventType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

public record StripeWebhookPayload(
        @JsonProperty("id") String id,
        @JsonProperty("type") String eventType,
        @JsonProperty("data") StripeData data
) implements GatewayPayload {

    public record StripeData(
            @JsonProperty("object") StripeObject object
    ) {}

    public record StripeObject(
            @JsonProperty("id") String paymentIntentId,
            @JsonProperty("amount") Long amountInCents,
            @JsonProperty("currency") String currency,
            @JsonProperty("created") Long createdEpochSeconds,
            @JsonProperty("metadata") Map<String, String> metadata
    ) {}

    @Override
    public String getGatewayTransactionId() {
        return data != null && data.object() != null ? data.object().paymentIntentId() : id;
    }

    @Override
    public String getOrderId() {
        if (data != null && data.object() != null && data.object().metadata() != null) {
            return data.object().metadata().getOrDefault("order_id", id);
        }
        return id;
    }

    @Override
    public BigDecimal getAmountInBaseUnit() {
        if (data != null && data.object() != null && data.object().amountInCents() != null) {
            return BigDecimal.valueOf(data.object().amountInCents())
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    @Override
    public String getCurrency() {
        return data != null && data.object() != null && data.object().currency() != null
                ? data.object().currency().toUpperCase() : "USD";
    }

    @Override
    public TransactionEventType getType() {
        return TransactionEventType.PAYMENT;
    }

    @Override
    public Instant getEventTimestamp() {
        if (data != null && data.object() != null && data.object().createdEpochSeconds() != null) {
            return Instant.ofEpochSecond(data.object().createdEpochSeconds());
        }
        return Instant.now();
    }
}