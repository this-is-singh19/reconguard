package io.reconguard.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Failure contract consumed by the AI worker. {@code gateway}, {@code errorCode} and {@code region}
 * are optional (nullable); the 8-arg constructor keeps older call sites compiling.
 */
public record TransactionFailureEvent(
        String transactionId,
        String tenantId,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant eventTimestamp,
        String gateway,
        String errorCode,
        String region
) {
    public TransactionFailureEvent(String transactionId, String tenantId, String orderId,
                                   BigDecimal amount, String currency, String status,
                                   String failureReason, Instant eventTimestamp) {
        this(transactionId, tenantId, orderId, amount, currency, status, failureReason, eventTimestamp,
                null, null, null);
    }
}
