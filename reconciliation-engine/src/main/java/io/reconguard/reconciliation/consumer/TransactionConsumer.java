package io.reconguard.reconciliation.consumer;

import io.reconguard.common.logging.TransactionMdc;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.reconciliation.config.KafkaConsumerConfig;
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
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = KafkaConsumerConfig.TRANSACTION_EVENT_LISTENER_FACTORY
    )
    public void consumeTransaction(@Payload TransactionEvent event) {
        // MDC ids are removed on scope exit, including when the listener throws.
        try (TransactionMdc ignored = TransactionMdc.open(
                event.tenantId(), event.orderId(), event.transactionId())) {
            process(event);
        }
    }

    private void process(TransactionEvent event) {
        log.info("Received transaction stream event from Kafka. TxId: {}, Type: {}",
                event.transactionId(), event.type());

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

        } catch (RuntimeException e) {
            log.error("Error while processing transaction stream record ID: {}. Evicting claim and rethrowing for retry/DLQ.",
                    event.transactionId(), e);

            // Evict the claim so the retry can run. Then rethrow: the DefaultErrorHandler retries and, after
            // the last attempt, publishes to the DLQ. Swallowing here would commit the offset and lose the event.
            try {
                idempotencyGuard.evictClaim(event.tenantId(), event.transactionId());
                log.debug("Evicted idempotency claim after failure for TxId: {}", event.transactionId());
            } catch (RuntimeException redisEx) {
                log.error("Failed to evict idempotency claim for TxId: {}", event.transactionId(), redisEx);
            }
            throw e;

        } finally {
            // ALWAYS clear the context pool after execution finishes to protect the thread
            TenantContext.clear();
            log.debug("TenantContext cleared for consumer thread processing.");
        }
    }
}
