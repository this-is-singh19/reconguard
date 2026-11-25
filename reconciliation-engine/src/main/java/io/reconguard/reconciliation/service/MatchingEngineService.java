package io.reconguard.reconciliation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reconguard.common.model.TransactionEvent;
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
     * Executes reconciliation matching algorithms on incoming transaction streaming entries.
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

        TransactionEvent bufferedCounterparty = windowBufferService.getBufferedCounterparty(event.tenantId(), event.orderId());
        if (isMatchingCounterparty(event, bufferedCounterparty)) {
            persistMatchedPair(event, bufferedCounterparty);
            windowBufferService.removeEvent(event.tenantId(), event.orderId());
            matchedCounter.increment();
            log.info("Reconciliation matched buffered legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), bufferedCounterparty.transactionId());
            return STATUS_MATCHED;
        }

        var databaseCounterparty = transactionRepository.findUnresolvedByOrderIdAndTenant(
                event.orderId(), event.tenantId());

        if (databaseCounterparty.isPresent() && isMatchingCounterparty(event, databaseCounterparty.get())) {
            TransactionRecord counterpart = databaseCounterparty.get();
            transactionRepository.save(toRecord(event, STATUS_MATCHED, counterpart.getId()));

            transactionRepository.linkAndMatch(
                    counterpart.getId(),
                    event.transactionId(),
                    event.tenantId(),
                    Instant.now()
            );

            windowBufferService.removeEvent(event.tenantId(), event.orderId());
            matchedCounter.increment();
            log.info("Reconciliation matched persisted legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), counterpart.getId());
            return STATUS_MATCHED;
        }

        log.info("No matching counterpart found within tolerance parameters. Saving leg and buffering Order ID: {}", event.orderId());
        transactionRepository.save(toRecord(event, STATUS_UNRESOLVED, null));
        windowBufferService.bufferEvent(event);
        unresolvedCounter.increment();
        return STATUS_UNRESOLVED;
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
        transactionRepository.save(toRecord(current, STATUS_MATCHED, buffered.transactionId()));
        transactionRepository.save(toRecord(buffered, STATUS_MATCHED, current.transactionId()));
    }

    private TransactionRecord toRecord(TransactionEvent event, String status, String counterpartyId) {
        TransactionRecord.TransactionRecordBuilder builder = TransactionRecord.builder()
                .id(event.transactionId())
                .tenantId(event.tenantId())
                .orderId(event.orderId())
                .amount(event.monetaryAmount().value())
                .currency(event.monetaryAmount().currency())
                .type(event.type().name())
                .status(status)
                .eventTimestamp(event.timestamp())
                .gatewayTransactionId(counterpartyId);

        if (STATUS_MATCHED.equals(status)) {
            builder.reconciledAt(Instant.now());
        }

        return builder.build();
    }
}