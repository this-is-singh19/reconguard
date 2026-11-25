-- A1.7: auto_applied flag (set by A2.7 for AUTO_RESOLVED) and lookup index for reconciliation_failures
ALTER TABLE resolution_suggestions
    ADD COLUMN IF NOT EXISTS auto_applied BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_failures_tenant_order
    ON reconciliation_failures (tenant_id, order_id);
