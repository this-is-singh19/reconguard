package io.reconguard.reconciliation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import io.reconguard.reconciliation.window.WindowBufferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class MatchingEngineService {

    private final TransactionRepository transactionRepository;
    private final WindowBufferService windowBufferService;
    private final MatchingToleranceConfig toleranceConfig;
    private final MeterRegistry meterRegistry;

    private final Counter matchedCounter;
    private final Counter unresolvedCounter;

    private static final String STATUS_MATCHED = "MATCHED";
    private static final String STATUS_UNRESOLVED = "UNRESOLVED";

    public MatchingEngineService(TransactionRepository transactionRepository,
                                 WindowBufferService windowBufferService,
                                 MatchingToleranceConfig toleranceConfig,
                                 MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.windowBufferService = windowBufferService;
        this.toleranceConfig = toleranceConfig;
        this.meterRegistry = meterRegistry;

        this.matchedCounter = Counter.builder("recon.matching.outcomes")
                .tag("outcome", "matched")
                .description("Number of successfully matched transaction pairs")
                .register(meterRegistry);

        this.unresolvedCounter = Counter.builder("recon.matching.outcomes")
                .tag("outcome", "unresolved")
                .description("Number of unmatched buffered transactions")
                .register(meterRegistry);
    }

    /**
     * Executes reconciliation matching for one incoming leg. Cardinality is 1:1 per side (A3.5): an incoming
     * leg is matched against the opposite side only. Matching is always scoped to the event's tenant (A3.12).
     */
    @Transactional
    public void reconcile(TransactionEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = STATUS_UNRESOLVED;
        try {
            status = processReconciliation(event);
        } finally {
            sample.stop(
                Timer.builder("recon.match.latency")
                        .description("Latency of the reconciliation window matching engine execution")
                        .tag("tenant", event.tenantId())
                        .tag("status", status)
                        .publishPercentiles(0.50, 0.95, 0.99)
                        .register(meterRegistry)
            );
        }
    }

    private String processReconciliation(TransactionEvent event) {
        log.info("Executing matching evaluation algorithm for TxId: {}, OrderId: {}",
                event.transactionId(), event.orderId());

        TransactionEventType counterpartSide = opposite(event.type());

        // 1. Fast path: the counterpart is still in the Redis window buffer
        TransactionEvent bufferedCounterparty = windowBufferService.getBufferedCounterparty(
                event.tenantId(), event.orderId(), counterpartSide);
        if (isMatchingCounterparty(event, bufferedCounterparty)) {
            persistMatchedPair(event, bufferedCounterparty);
            windowBufferService.removeEvent(event.tenantId(), event.orderId(), counterpartSide);
            matchedCounter.increment();
            log.info("Reconciliation matched buffered legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), bufferedCounterparty.transactionId());
            return STATUS_MATCHED;
        }

        // 2. Slow path: any UNRESOLVED opposite-side leg of this order in the DB (survives Redis TTL/restart)
        TransactionRecord dbCounterpart = transactionRepository
                .findUnresolvedByOrderIdAndTenant(event.orderId(), event.tenantId())
                .stream()
                .filter(candidate -> isMatchingCounterparty(event, candidate))
                .findFirst()
                .orElse(null);

        if (dbCounterpart != null) {
            upsertLeg(event, STATUS_MATCHED, dbCounterpart.getId());
            int linked = transactionRepository.linkAndMatch(
                    dbCounterpart.getId(), event.transactionId(), event.tenantId(), Instant.now());
            if (linked != 1) {
                // Counterpart changed state between read and update (e.g. swept to FAILED). Roll back this tx.
                throw new IllegalStateException("Counterpart " + dbCounterpart.getId() + " changed state during match");
            }

            windowBufferService.removeEvent(event.tenantId(), event.orderId(), counterpartSide);
            matchedCounter.increment();
            log.info("Reconciliation matched persisted legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), dbCounterpart.getId());
            return STATUS_MATCHED;
        }

        // 3. No counterpart: keep the leg durable in the DB and buffer it for the window
        log.info("No matching counterpart found within tolerance parameters. Saving leg and buffering Order ID: {}", event.orderId());
        upsertLeg(event, STATUS_UNRESOLVED, null);
        windowBufferService.bufferEvent(event);
        unresolvedCounter.increment();
        return STATUS_UNRESOLVED;
    }

    private static TransactionEventType opposite(TransactionEventType type) {
        return type == TransactionEventType.ORDER ? TransactionEventType.PAYMENT : TransactionEventType.ORDER;
    }

    private boolean isMatchingCounterparty(TransactionEvent event, TransactionEvent counterparty) {
        if (counterparty == null) {
            return false;
        }

        return !event.type().name().equalsIgnoreCase(counterparty.type().name())
                && hasSameCurrency(event, counterparty.monetaryAmount().currency())
                && isAmountWithinTolerance(event.monetaryAmount().value(), counterparty.monetaryAmount().value())
                && isTimestampWithinTolerance(event.timestamp(), counterparty.timestamp());
    }

    private boolean isMatchingCounterparty(TransactionEvent event, TransactionRecord counterparty) {
        boolean isCounterpartyOrder = "ORDER".equalsIgnoreCase(counterparty.getType());
        boolean isEventOrder = "ORDER".equalsIgnoreCase(event.type().name());

        if (isEventOrder == isCounterpartyOrder) {
            return false; // Both are the same leg type
        }

        return hasSameCurrency(event, counterparty.getCurrency())
                && isAmountWithinTolerance(event.monetaryAmount().value(), counterparty.getAmount())
                && isTimestampWithinTolerance(event.timestamp(), counterparty.getEventTimestamp());
    }

    private boolean hasSameCurrency(TransactionEvent event, String counterpartyCurrency) {
        return event.monetaryAmount().currency().equalsIgnoreCase(counterpartyCurrency);
    }

    private boolean isAmountWithinTolerance(BigDecimal currentAmount, BigDecimal counterpartyAmount) {
        return currentAmount.subtract(counterpartyAmount).abs().compareTo(toleranceConfig.maxAmountDifference()) <= 0;
    }

    private boolean isTimestampWithinTolerance(Instant currentTimestamp, Instant counterpartyTimestamp) {
        return Duration.between(currentTimestamp, counterpartyTimestamp).abs().compareTo(toleranceConfig.maxTimeWindow()) <= 0;
    }

    private void persistMatchedPair(TransactionEvent current, TransactionEvent buffered) {
        // The buffered leg already has a DB row (saved as UNRESOLVED when it was buffered): update it, do not re-insert.
        upsertLeg(current, STATUS_MATCHED, buffered.transactionId());
        upsertLeg(buffered, STATUS_MATCHED, current.transactionId());
    }

    /**
     * Inserts or updates one leg by transaction id. A row owned by another tenant is never touched (A3.12):
     * transaction ids are global primary keys, so a collision across tenants is rejected, not overwritten.
     */
    private void upsertLeg(TransactionEvent event, String status, String counterpartId) {
        var existing = transactionRepository.findById(event.transactionId());
        if (existing.isPresent() && !event.tenantId().equals(existing.get().getTenantId())) {
            throw new IllegalArgumentException(
                    "Transaction id " + event.transactionId() + " already belongs to another tenant");
        }

        TransactionRecord record = existing.orElseGet(TransactionRecord::new);
        record.setId(event.transactionId());
        record.setTenantId(event.tenantId());
        record.setOrderId(event.orderId());
        record.setAmount(event.monetaryAmount().value());
        record.setCurrency(event.monetaryAmount().currency());
        record.setType(event.type().name());
        record.setStatus(status);
        record.setEventTimestamp(event.timestamp());
        record.setMatchedWithTransactionId(counterpartId);
        transactionRepository.save(record);
    }
}
