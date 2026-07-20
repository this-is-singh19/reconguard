package io.reconguard.ingestion.service;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

        // Enforce strict currency casing at the domain level
        MonetaryAmount safeAmount = new MonetaryAmount(
                rawEvent.monetaryAmount().value(),
                rawEvent.monetaryAmount().currency()
        );

        // Build a canonical record bound to our structural context
        TransactionEvent normalizedEvent = new TransactionEvent(
                rawEvent.transactionId().strip(),
                activeTenant, // Inject our validated tenant ID context
                rawEvent.orderId().strip(),
                safeAmount,
                rawEvent.type(),
                normalizedTimestamp
        );

        log.info("Successfully normalized transaction event: ID={}, Tenant={}",
                normalizedEvent.transactionId(), normalizedEvent.tenantId());

        return normalizedEvent;
    }
}