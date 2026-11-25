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
    status VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',
    event_timestamp TIMESTAMPTZ NOT NULL,
    matched_with_transaction_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    -- required so resolution_suggestions (V5) can FK on (tenant_id, transaction_id)
    CONSTRAINT uq_transactions_tenant_tx UNIQUE (tenant_id, transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_tx_tenant_order_status
    ON transactions(tenant_id, order_id, status);

CREATE INDEX IF NOT EXISTS idx_tx_tenant_status
    ON transactions(tenant_id, status);
