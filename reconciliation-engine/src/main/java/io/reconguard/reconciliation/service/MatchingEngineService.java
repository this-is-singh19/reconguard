package io.reconguard.reconciliation.service;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.reconciliation.model.TransactionRecord;
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

    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.02");
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofSeconds(120);
    private static final String STATUS_MATCHED = "MATCHED";
    private static final String STATUS_UNRESOLVED = "UNRESOLVED";

    public MatchingEngineService(TransactionRepository transactionRepository,
                                 WindowBufferService windowBufferService) {
        this.transactionRepository = transactionRepository;
        this.windowBufferService = windowBufferService;
    }

    /**
     * Executes reconciliation matching algorithms on incoming transaction streaming entries.
     */
    @Transactional
    public void reconcile(TransactionEvent event) {
        log.info("Executing matching evaluation algorithm for TxId: {}, OrderId: {}",
                event.transactionId(), event.orderId());

        TransactionEvent bufferedCounterparty = windowBufferService.getBufferedCounterparty(event.tenantId(), event.orderId());
        if (isMatchingCounterparty(event, bufferedCounterparty)) {
            persistMatchedPair(event, bufferedCounterparty);
            windowBufferService.removeEvent(event.tenantId(), event.orderId());
            log.info("Reconciliation matched buffered legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), bufferedCounterparty.transactionId());
            return;
        }

        var databaseCounterparty = transactionRepository.findUnresolvedByOrderIdAndTenant(
                event.orderId(), event.tenantId());

        if (databaseCounterparty.isPresent() && isMatchingCounterparty(event, databaseCounterparty.get())) {
            TransactionRecord counterpart = databaseCounterparty.get();
            transactionRepository.save(toRecord(event, STATUS_MATCHED, counterpart.getTransactionId()));
            transactionRepository.linkAndMatch(counterpart.getTransactionId(), event.transactionId(), event.tenantId());
            windowBufferService.removeEvent(event.tenantId(), event.orderId());
            log.info("Reconciliation matched persisted legs. Current TxId: {}, Counterparty TxId: {}",
                    event.transactionId(), counterpart.getTransactionId());
            return;
        }

        log.info("No matching counterpart found within tolerance parameters. Saving leg and buffering Order ID: {}", event.orderId());
        transactionRepository.save(toRecord(event, STATUS_UNRESOLVED, null));
        windowBufferService.bufferEvent(event);
    }

    private boolean isMatchingCounterparty(TransactionEvent event, TransactionEvent counterparty) {
        if (counterparty == null) {
            return false;
        }

        return hasDifferentLegTypes(event, counterparty.type().name())
                && hasSameCurrency(event, counterparty.monetaryAmount().currency())
                && isAmountWithinTolerance(event.monetaryAmount().value(), counterparty.monetaryAmount().value())
                && isTimestampWithinTolerance(event.timestamp(), counterparty.timestamp());
    }

    private boolean isMatchingCounterparty(TransactionEvent event, TransactionRecord counterparty) {
        return hasDifferentLegTypes(event, counterparty.getType())
                && hasSameCurrency(event, counterparty.getCurrency())
                && isAmountWithinTolerance(event.monetaryAmount().value(), counterparty.getAmount())
                && isTimestampWithinTolerance(event.timestamp(), counterparty.getEventTimestamp());
    }

    private boolean hasDifferentLegTypes(TransactionEvent event, String counterpartyType) {
        return !event.type().name().equalsIgnoreCase(counterpartyType);
    }

    private boolean hasSameCurrency(TransactionEvent event, String counterpartyCurrency) {
        return event.monetaryAmount().currency().equalsIgnoreCase(counterpartyCurrency);
    }

    private boolean isAmountWithinTolerance(BigDecimal currentAmount, BigDecimal counterpartyAmount) {
        return currentAmount.subtract(counterpartyAmount).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
    }

    private boolean isTimestampWithinTolerance(Instant currentTimestamp, Instant counterpartyTimestamp) {
        return Duration.between(currentTimestamp, counterpartyTimestamp).abs().compareTo(TIMESTAMP_TOLERANCE) <= 0;
    }

    private void persistMatchedPair(TransactionEvent current, TransactionEvent buffered) {
        transactionRepository.save(toRecord(current, STATUS_MATCHED, buffered.transactionId()));
        transactionRepository.save(toRecord(buffered, STATUS_MATCHED, current.transactionId()));
    }

    private TransactionRecord toRecord(TransactionEvent event, String status, String matchedWithTransactionId) {
        return TransactionRecord.builder()
                .transactionId(event.transactionId())
                .tenantId(event.tenantId())
                .orderId(event.orderId())
                .amount(event.monetaryAmount().value())
                .currency(event.monetaryAmount().currency())
                .type(event.type().name())
                .status(status)
                .eventTimestamp(event.timestamp())
                .matchedWithTransactionId(matchedWithTransactionId)
                .build();
    }
}
