package io.reconguard.reconciliation.scheduler;

import io.reconguard.reconciliation.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
public class UnresolvedTransactionSweeper {

    private final TransactionRepository transactionRepository;

    public UnresolvedTransactionSweeper(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Periodically sweeps PostgreSQL for transactions that remain UNRESOLVED
     * beyond the allowable SLAs (e.g., older than 24 hours).
     */
    @Scheduled(cron = "0 0 * * * *") // Runs every hour at the top of the hour
    @Transactional(readOnly = true)
    public void sweepStaleTransactions() {
        Instant cutoffTime = Instant.now().minus(24, ChronoUnit.HOURS);
        log.info("Executing sweep for UNRESOLVED transactions created prior to threshold: {}", cutoffTime);

        try {
            // Count or process orphaned records beyond the window
            long staleCount = transactionRepository.countByStatusAndCreatedAtBefore("UNRESOLVED", cutoffTime);

            if (staleCount > 0) {
                log.warn("Found {} orphaned UNRESOLVED transaction(s) exceeding 24-hour threshold.", staleCount);
                // Here you can emit metrics, dispatch alerts, or move to an audit table
            } else {
                log.info("Sweep complete. No stale UNRESOLVED transactions found.");
            }
        } catch (Exception ex) {
            log.error("Failed to execute unresolved transaction sweep", ex);
        }
    }
}