package io.reconguard.ingestion.dto;

import io.reconguard.common.model.TransactionEventType;

import java.math.BigDecimal;
import java.time.Instant;

public interface GatewayPayload {
    String getGatewayTransactionId();
    String getOrderId();
    BigDecimal getAmountInBaseUnit();
    String getCurrency();
    TransactionEventType getType();
    Instant getEventTimestamp();
}