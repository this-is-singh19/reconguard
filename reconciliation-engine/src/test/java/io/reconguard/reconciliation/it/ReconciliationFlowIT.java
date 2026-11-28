package io.reconguard.reconciliation.it;

import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.window.WindowUnresolvedTransactionSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** B3: reconciliation engine against real Postgres (Flyway schema), Redis and Kafka. */
class ReconciliationFlowIT extends AbstractEngineIT {

    private static final String TENANT = "tenant-it";

    @Autowired
    private WindowUnresolvedTransactionSweeper sweeper;

    private String matchedWith(String tx) {
        return jdbc.queryForObject("SELECT matched_with_transaction_id FROM transactions WHERE transaction_id = ?",
                String.class, tx);
    }

    @Test
    @DisplayName("ORDER then PAYMENT within tolerance end as MATCHED, linked to each other")
    void matchesPair() {
        String order = uid("ord"), o = uid("tx-o"), p = uid("tx-p");
        Instant now = Instant.now();
        send(event(o, TENANT, order, TransactionEventType.ORDER, "100.00", "USD", now));
        awaitStatus(o, "UNRESOLVED");
        send(event(p, TENANT, order, TransactionEventType.PAYMENT, "100.01", "USD", now.plusSeconds(5)));

        awaitStatus(o, "MATCHED");
        awaitStatus(p, "MATCHED");
        assertThat(matchedWith(o)).isEqualTo(p);
        assertThat(matchedWith(p)).isEqualTo(o);
    }

    @Test
    @DisplayName("Counterpart still matches from Postgres when the Redis window buffer is gone")
    void matchesViaDatabaseWhenBufferLost() {
        String order = uid("ord"), o = uid("tx-o"), p = uid("tx-p");
        Instant now = Instant.now();
        send(event(o, TENANT, order, TransactionEventType.ORDER, "50.00", "EUR", now));
        awaitStatus(o, "UNRESOLVED");
        stringRedis.delete(stringRedis.keys("reconguard:window:buffer:*"));

        send(event(p, TENANT, order, TransactionEventType.PAYMENT, "50.00", "EUR", now));

        awaitStatus(o, "MATCHED");
        awaitStatus(p, "MATCHED");
    }

    @Test
    @DisplayName("Out-of-tolerance legs are not matched")
    void doesNotMatchOutsideTolerance() {
        String order = uid("ord"), o = uid("tx-o"), p = uid("tx-p");
        Instant now = Instant.now();
        send(event(o, TENANT, order, TransactionEventType.ORDER, "100.00", "USD", now));
        awaitStatus(o, "UNRESOLVED");
        send(event(p, TENANT, order, TransactionEventType.PAYMENT, "105.00", "USD", now));

        awaitStatus(p, "UNRESOLVED");
        assertThat(txStatus(o)).isEqualTo("UNRESOLVED");
    }

    @Test
    @DisplayName("A re-delivered leg is dropped: an already MATCHED leg is not reset")
    void duplicateEventIsIgnored() {
        String order = uid("ord"), o = uid("tx-o"), p = uid("tx-p"), sentinel = uid("tx-s");
        Instant now = Instant.now();
        send(event(o, TENANT, order, TransactionEventType.ORDER, "10.00", "USD", now));
        awaitStatus(o, "UNRESOLVED");
        send(event(p, TENANT, order, TransactionEventType.PAYMENT, "10.00", "USD", now));
        awaitStatus(o, "MATCHED");
        awaitStatus(p, "MATCHED");

        // Duplicate, then a sentinel on the same key (same partition, so processed after the duplicate)
        send(event(o, TENANT, order, TransactionEventType.ORDER, "10.00", "USD", now));
        send(event(sentinel, TENANT, order, TransactionEventType.ORDER, "999.00", "USD", now));
        awaitStatus(sentinel, "UNRESOLVED");

        assertThat(txStatus(o)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE transaction_id = ?", Integer.class, o))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Tenants are isolated: same order id across tenants never matches")
    void tenantIsolation() {
        String order = uid("ord"), a = uid("tx-a"), b = uid("tx-b");
        Instant now = Instant.now();
        send(event(a, "tenant-A", order, TransactionEventType.ORDER, "20.00", "USD", now));
        awaitStatus(a, "UNRESOLVED");
        send(event(b, "tenant-B", order, TransactionEventType.PAYMENT, "20.00", "USD", now));
        awaitStatus(b, "UNRESOLVED");

        assertThat(txStatus(a)).isEqualTo("UNRESOLVED");
        assertThat(matchedWith(a)).isNull();
        assertThat(matchedWith(b)).isNull();
    }

    @Test
    @DisplayName("A transaction id owned by another tenant goes to the DLQ and is never overwritten")
    void crossTenantTransactionIdGoesToDlq() {
        String shared = uid("tx-shared");
        Instant now = Instant.now();
        send(event(shared, "tenant-A", uid("ord"), TransactionEventType.ORDER, "30.00", "USD", now));
        awaitStatus(shared, "UNRESOLVED");

        String orderB = uid("ord-b");
        send(event(shared, "tenant-B", orderB, TransactionEventType.ORDER, "30.00", "USD", now));

        var dlq = awaitRecord(DLQ_TOPIC, r -> r.value() != null && r.value().contains("tenant-B") && r.value().contains(shared));
        assertThat(dlq.key()).isEqualTo("tenant-B:" + orderB);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM transactions WHERE transaction_id = ?", String.class, shared))
                .isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("Unmatched leg past the window: sweeper marks FAILED, records the failure, publishes the event")
    void timeoutBecomesFailedAndPublishesFailureEvent() {
        String order = uid("ord"), tx = uid("tx-lonely");
        send(event(tx, TENANT, order, TransactionEventType.ORDER, "75.00", "USD", Instant.now()));
        awaitStatus(tx, "UNRESOLVED");

        jdbc.update("UPDATE transactions SET created_at = ? WHERE transaction_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), tx);
        sweeper.sweepStaleUnresolvedTransactions();

        assertThat(txStatus(tx)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_failures WHERE tenant_id = ? AND transaction_id = ?",
                Integer.class, TENANT, tx)).isEqualTo(1);

        var failure = awaitRecord(FAILURES_TOPIC, r -> r.value() != null && r.value().contains(tx));
        assertThat(failure.key()).isEqualTo(TENANT + ":" + order);
        assertThat(failure.value()).contains(TENANT).contains("FAILED");

        // Sweeping again does not duplicate the failure row
        sweeper.sweepStaleUnresolvedTransactions();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_failures WHERE transaction_id = ?", Integer.class, tx)).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent ORDER/PAYMENT legs across many orders all match, none fail or double-match")
    void concurrentLegsAllMatch() {
        int orders = 25;
        Instant now = Instant.now();
        List<String> txIds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<CompletableFuture<Void>> sends = new ArrayList<>();
            for (int i = 0; i < orders; i++) {
                String order = uid("ord"), o = uid("tx-o"), p = uid("tx-p");
                txIds.add(o);
                txIds.add(p);
                sends.add(CompletableFuture.runAsync(
                        () -> send(event(o, TENANT, order, TransactionEventType.ORDER, "10.00", "USD", now)), pool));
                sends.add(CompletableFuture.runAsync(
                        () -> send(event(p, TENANT, order, TransactionEventType.PAYMENT, "10.00", "USD", now)), pool));
            }
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE status = 'MATCHED'", Integer.class))
                        .isEqualTo(orders * 2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(orders * 2);
        // Every leg points at its own counterpart, and the pointer is mutual
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM transactions a JOIN transactions b
                  ON a.matched_with_transaction_id = b.transaction_id AND b.matched_with_transaction_id = a.transaction_id
                """, Integer.class)).isEqualTo(orders * 2);
    }
}
