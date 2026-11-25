package io.reconguard.ai.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the AI worker's results back to {@code reconciliation_failures} (A2.7/A2.8). The row itself is
 * created by the engine sweeper (engine owns the entity); the worker only attaches the context embedding,
 * looks up the failure id for linking, and flags auto-resolved failures. Plain SQL because the
 * pgvector column is not mapped by JPA.
 */
@Repository
public class FailureRecordRepository {

    private final JdbcTemplate jdbc;

    public FailureRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the context embedding on the failure row and returns its id, or empty if no row exists yet. */
    public Optional<UUID> attachEmbedding(String tenantId, String transactionId, float[] embedding) {
        if (embedding != null && embedding.length > 0) {
            jdbc.update("UPDATE reconciliation_failures SET context_embedding = CAST(? AS vector) "
                            + "WHERE tenant_id = ? AND transaction_id = ?",
                    Arrays.toString(embedding), tenantId, transactionId);
        }
        return findId(tenantId, transactionId);
    }

    /** AUTO_RESOLVED: flag the failure as resolved (no money-moving action is taken, see G4.4). */
    public int markResolved(String tenantId, String transactionId) {
        return jdbc.update("UPDATE reconciliation_failures SET resolved = TRUE WHERE tenant_id = ? AND transaction_id = ?",
                tenantId, transactionId);
    }

    private Optional<UUID> findId(String tenantId, String transactionId) {
        return jdbc.query("SELECT id FROM reconciliation_failures WHERE tenant_id = ? AND transaction_id = ?",
                        (rs, i) -> rs.getObject("id", UUID.class), tenantId, transactionId)
                .stream().findFirst();
    }
}
