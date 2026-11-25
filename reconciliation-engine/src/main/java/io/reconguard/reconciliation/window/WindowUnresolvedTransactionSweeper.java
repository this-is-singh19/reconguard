package io.reconguard.reconciliation.window;

import io.reconguard.reconciliation.consumer.FailurePublisher;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.domain.ReconciliationFailure;
import io.reconguard.reconciliation.repository.ReconciliationFailureRepository;
import io.reconguard.reconciliation.repository.TransactionRepository;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class WindowUnresolvedTransactionSweeper {

    private final TransactionRepository transactionRepository;
    private final FailurePublisher failurePublisher;
    private final ReconciliationFailureRepository failureRepository;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String FAILURE_REASON = "Unmatched window expiration";

    private static final Duration EXPIRATION_WINDOW = Duration.ofMinutes(5);

    public WindowUnresolvedTransactionSweeper(TransactionRepository transactionRepository,
                                              FailurePublisher failurePublisher,
                                              ReconciliationFailureRepository failureRepository) {
        this.failureRepository = failureRepository;
        this.transactionRepository = transactionRepository;
        this.failurePublisher = failurePublisher;
    }

    /**
     * Periodic sweeper job to detect stale unresolved transactions, mark them as FAILED,
     * and emit failure events to Kafka for Phase 3 AI diagnostic processing.
     */
    @Scheduled(fixedDelayString = "${reconguard.sweeper.interval-ms:60000}")
    @Transactional
    public void sweepStaleUnresolvedTransactions() {
        Instant cutoff = Instant.now().minus(EXPIRATION_WINDOW);
        log.debug("Executing unresolved transaction sweep for events prior to: {}", cutoff);

        List<TransactionRecord> staleRecords = transactionRepository.findStaleUnresolved(cutoff);

        if (staleRecords.isEmpty()) {
            return;
        }

        log.info("Found {} stale unresolved transactions eligible for failure transition.", staleRecords.size());

        List<String> staleIds = staleRecords.stream()
                .map(TransactionRecord::getId)
                .toList();

        // 1. Transition database status to FAILED
        transactionRepository.markAsFailed(staleIds);

        // 2. Delegate failure event emission to FailurePublisher
        for (TransactionRecord record : staleRecords) {
            Instant timestamp = record.getEventTimestamp() != null
                    ? record.getEventTimestamp()
                    : record.getCreatedAt();

            TransactionEventType type = "ORDER".equalsIgnoreCase(record.getType())
                    ? TransactionEventType.ORDER
                    : TransactionEventType.PAYMENT;

            TransactionEvent event = new TransactionEvent(
                    record.getId(),
                    record.getTenantId(),
                    record.getOrderId(),
                    new MonetaryAmount(record.getAmount(), record.getCurrency()),
                    type,
                    timestamp
            );

            recordFailure(record, timestamp);
            failurePublisher.publishFailure(record.getTenantId(), event, FAILURE_REASON);
        }

        log.info("Successfully swept and transitioned {} unresolved transactions to FAILED state.", staleRecords.size());
    }

    /** Persists the failure row (A2.8) unless one already exists for (tenant, transaction). */
    private void recordFailure(TransactionRecord record, Instant timestamp) {
        if (failureRepository.existsByTenantIdAndTransactionId(record.getTenantId(), record.getId())) {
            return;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("transactionId", record.getId());
        ctx.put("orderId", record.getOrderId());
        ctx.put("type", record.getType());
        ctx.put("amount", record.getAmount());
        ctx.put("currency", record.getCurrency());
        ctx.put("eventTimestamp", timestamp);
        String payload;
        try {
            payload = JSON.writeValueAsString(ctx);
        } catch (Exception e) {
            payload = ctx.toString();
        }
        failureRepository.save(ReconciliationFailure.builder()
                .id(UUID.randomUUID())
                .tenantId(record.getTenantId())
                .orderId(record.getOrderId())
                .transactionId(record.getId())
                .failureReason(FAILURE_REASON)
                .rawPayloadContext(payload)
                .resolved(false)
                .createdAt(Instant.now())
                .build());
    }
}
