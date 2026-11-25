package io.reconguard.ingestion.service;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.ingestion.dto.GatewayPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Slf4j
@Service
public class NormalizationService {

    /**
     * Normalizes the incoming transaction event by binding the structural tenant context
     * and ensuring timestamps are fully synchronized.
     */
    public TransactionEvent normalize(TransactionEvent rawEvent) {
        log.debug("Normalizing transaction event: {}", rawEvent.transactionId());

        // Extract the safe context extracted by our TenantInterceptor
        String activeTenant = TenantContext.getTenantId();

        // Handle timestamp fallbacks safely if the provider didn't pass one
        Instant normalizedTimestamp = rawEvent.timestamp() != null ? rawEvent.timestamp() : Instant.now();

        // Enforce strict currency casing and decimal scale at the domain level
        MonetaryAmount safeAmount = new MonetaryAmount(
                rawEvent.monetaryAmount().value().setScale(4, RoundingMode.HALF_UP),
                rawEvent.monetaryAmount().currency().trim().toUpperCase()
        );

        // Build a canonical record bound to our structural context
        TransactionEvent normalizedEvent = new TransactionEvent(
                rawEvent.transactionId().strip(),
                activeTenant, // Inject our validated tenant ID context
                rawEvent.orderId().strip(),
                safeAmount,
                rawEvent.type(),
                normalizedTimestamp,
                trimToNull(rawEvent.gateway()),
                trimToNull(rawEvent.errorCode()),
                trimToNull(rawEvent.region())
        );

        log.info("Successfully normalized transaction event: ID={}, Tenant={}",
                normalizedEvent.transactionId(), normalizedEvent.tenantId());

        return normalizedEvent;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /**
     * Normalizes gateway-specific payloads (Stripe, PayPal, etc.) into the canonical TransactionEvent record.
     */
    public TransactionEvent normalize(GatewayPayload payload) {
        log.debug("Normalizing gateway payload for Tx: {}", payload.getGatewayTransactionId());

        String activeTenant = TenantContext.getTenantId();
        Instant eventTimestamp = payload.getEventTimestamp() != null ? payload.getEventTimestamp() : Instant.now();

        BigDecimal scaledAmount = payload.getAmountInBaseUnit().setScale(4, RoundingMode.HALF_UP);
        MonetaryAmount monetaryAmount = new MonetaryAmount(scaledAmount, payload.getCurrency().trim().toUpperCase());

        TransactionEvent canonicalEvent = new TransactionEvent(
                payload.getGatewayTransactionId().strip(),
                activeTenant,
                payload.getOrderId().strip(),
                monetaryAmount,
                payload.getType(),
                eventTimestamp
        );

        log.info("Successfully transformed gateway payload into canonical TransactionEvent: TxID={}, OrderID={}, Tenant={}",
                canonicalEvent.transactionId(), canonicalEvent.orderId(), canonicalEvent.tenantId());

        return canonicalEvent;
    }
}