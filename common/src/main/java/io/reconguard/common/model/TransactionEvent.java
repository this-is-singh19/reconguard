package io.reconguard.common.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Canonical transaction event. {@code gateway}, {@code errorCode} and {@code region} are optional
 * (nullable) and were added after the initial contract; the 6-arg constructor keeps older call sites
 * compiling. Older JSON payloads without these properties still deserialize (they become null).
 */
public record TransactionEvent(
        @NotBlank(message = "Transaction ID cannot be blank")
        String transactionId,

        @NotBlank(message = "Tenant ID cannot be blank")
        String tenantId,

        @NotBlank(message = "Order ID cannot be blank")
        String orderId,

        @NotNull(message = "Monetary amount block is required")
        @Valid // Critical: Forces Spring to validate fields inside the MonetaryAmount record too
        MonetaryAmount monetaryAmount,

        @NotNull(message = "Transaction type is required (ORDER or PAYMENT)")
        TransactionEventType type,

        @NotNull(message = "Timestamp is required")
        Instant timestamp,

        String gateway,
        String errorCode,
        String region
) {
    public TransactionEvent(String transactionId, String tenantId, String orderId,
                            MonetaryAmount monetaryAmount, TransactionEventType type, Instant timestamp) {
        this(transactionId, tenantId, orderId, monetaryAmount, type, timestamp, null, null, null);
    }
}
