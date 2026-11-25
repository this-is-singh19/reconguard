package io.reconguard.common.event;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionFailureEvent(
        String transactionId,
        String tenantId,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant eventTimestamp
) {}