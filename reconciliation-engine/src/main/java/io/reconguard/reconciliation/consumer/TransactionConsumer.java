package io.reconguard.reconciliation.consumer;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.reconciliation.idempotency.IdempotencyGuard;
import io.reconguard.reconciliation.service.MatchingEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TransactionConsumer {

    private final MatchingEngineService matchingEngineService;
    private final IdempotencyGuard idempotencyGuard;

    public TransactionConsumer(MatchingEngineService matchingEngineService,
                               IdempotencyGuard idempotencyGuard) {
        this.matchingEngineService = matchingEngineService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @KafkaListener(
            topics = "reconguard.transactions.raw",
            groupId = "reconguard-reconciliation-group"
    )
    public void consumeTransaction(@Payload TransactionEvent event) {
        log.info("Received transaction stream event from Kafka. TxId: {}, Type: {}",
                event.transactionId(), event.type());

        // --- TEMPORARY DLQ TEST TRIGGER ---
        if (event.transactionId() != null && event.transactionId().startsWith("FAIL-TEST")) {
            throw new RuntimeException("Intentional failure forced for DLQ verification! TxId: " + event.transactionId());
        }

        // 1. Evaluate the Atomic Idempotency Guard first
        if (!idempotencyGuard.checkAndClaim(event.tenantId(), event.transactionId())) {
            log.warn("Deduplication Guard Triggered! Duplicate event dropped. Tenant: {}, TxId: {}",
                    event.tenantId(), event.transactionId());
            return; // Clean short-circuit. Kafka offset commits normally.
        }

        try {
            // CRITICAL: Hydrate our execution context with this message's specific tenant ID.
            // This ensures downstream database logic filters data accurately for this background thread.
            TenantContext.setTenantId(event.tenantId());
            log.debug("TenantContext successfully bound to ID: {}", event.tenantId());

            matchingEngineService.reconcile(event);
            log.info("Successfully processed transaction record: {}", event.transactionId());

        } catch (Exception e) {
            log.error("Critical error while processing transaction stream record ID: {}. Error: {}",
                    event.transactionId(), e.getMessage(), e);

            // 3. Resiliency Action: Evict cache claim on downstream failure so a future retry can run
            try {
                idempotencyGuard.evictClaim(event.tenantId(), event.transactionId());
                log.debug("Successfully evicted idempotency lock following execution failure for TxId: {}", event.transactionId());
            } catch (Exception redisEx) {
                log.error("Failed to evict idempotency token from Redis for TxId: {}", event.transactionId(), redisEx);
            }

        } finally {
            // ALWAYS clear the context pool after execution finishes to protect the thread
            TenantContext.clear();
            log.debug("TenantContext cleared for consumer thread processing.");
        }
    }
}