CREATE TABLE IF NOT EXISTS tenants (
                         tenant_id VARCHAR(64) PRIMARY KEY,
                         name VARCHAR(100) NOT NULL,
                         created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
                              transaction_id VARCHAR(64) PRIMARY KEY,
                              tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(tenant_id),
                              order_id VARCHAR(64) NOT NULL,
                              amount NUMERIC(18, 4) NOT NULL,
                              currency VARCHAR(3) NOT NULL,
                              type VARCHAR(32) NOT NULL,
                              status VARCHAR(32) NOT NULL,
                              event_timestamp TIMESTAMPTZ NOT NULL,
                              matched_with_transaction_id VARCHAR(64),
                              created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_tx_tenant_order_status
    ON transactions(tenant_id, order_id, status);

CREATE INDEX IF NOT EXISTS idx_tx_tenant_status
    ON transactions(tenant_id, status);

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