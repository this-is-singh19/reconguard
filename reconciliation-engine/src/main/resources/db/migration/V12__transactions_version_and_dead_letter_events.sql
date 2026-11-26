-- A3.11: optimistic locking column for TransactionRecord (@Version)
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- A3.3: persisted dead letters (DeadLetterConsumer writes here instead of only logging)
CREATE TABLE IF NOT EXISTS dead_letter_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64),
    order_id VARCHAR(64),
    transaction_id VARCHAR(64),
    original_topic VARCHAR(255),
    dlq_topic VARCHAR(255) NOT NULL,
    failure_reason TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_tenant_received
    ON dead_letter_events (tenant_id, received_at);
