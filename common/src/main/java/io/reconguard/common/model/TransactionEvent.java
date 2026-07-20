package io.reconguard.common.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

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
        Instant timestamp
) {}