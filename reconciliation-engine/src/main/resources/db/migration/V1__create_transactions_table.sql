CREATE TABLE IF NOT EXISTS transactions (
                                            transaction_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
                                  matched_with_transaction_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
                                  );

CREATE INDEX IF NOT EXISTS idx_transactions_order_tenant
    ON transactions(tenant_id, order_id, status);