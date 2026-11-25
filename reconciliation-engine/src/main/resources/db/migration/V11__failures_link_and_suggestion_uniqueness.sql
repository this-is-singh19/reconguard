-- A2.8: reconciliation_failures gets the transaction id (written by the engine sweeper) and the
-- context embedding (written by the AI worker). A2.9: one suggestion per (tenant, transaction).
ALTER TABLE reconciliation_failures
    ADD COLUMN IF NOT EXISTS transaction_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS context_embedding vector(768);

CREATE UNIQUE INDEX IF NOT EXISTS uq_failures_tenant_tx
    ON reconciliation_failures (tenant_id, transaction_id);

ALTER TABLE resolution_suggestions
    ADD COLUMN IF NOT EXISTS failure_id UUID REFERENCES reconciliation_failures(id) ON DELETE SET NULL;

-- Keep the newest suggestion per (tenant, transaction) before enforcing uniqueness.
DELETE FROM resolution_suggestions a
    USING resolution_suggestions b
    WHERE a.tenant_id = b.tenant_id
      AND a.transaction_id = b.transaction_id
      AND (a.created_at < b.created_at
           OR (a.created_at = b.created_at AND a.suggestion_id < b.suggestion_id));

CREATE UNIQUE INDEX IF NOT EXISTS uq_resolution_suggestions_tenant_tx
    ON resolution_suggestions (tenant_id, transaction_id);
