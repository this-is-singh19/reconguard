CREATE TABLE IF NOT EXISTS reconciliation_failures (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(tenant_id),
    order_id VARCHAR(64) NOT NULL,
    failure_reason VARCHAR(255) NOT NULL,
    raw_payload_context TEXT NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_failures_unresolved
    ON reconciliation_failures(tenant_id)
    WHERE resolved = FALSE;

INSERT INTO tenants (tenant_id, name)
VALUES ('tenant-space-99', 'Acme Global Local Workspace')
ON CONFLICT (tenant_id) DO NOTHING;
