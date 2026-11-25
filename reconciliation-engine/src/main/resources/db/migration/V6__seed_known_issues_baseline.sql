-- Seed baseline operational failure patterns for known_issues
-- Embeddings are left NULL here. The ai-diagnostic-worker KnownIssueEmbeddingBootstrap fills them with the real model at startup.

INSERT INTO known_issues (issue_id, pattern_name, category, description, recommended_action, created_at, updated_at)
VALUES
(
    'ISSUE-001',
    'FX Conversion Rate Drift',
    'CURRENCY_MISMATCH',
    'Transaction amount differs due to foreign exchange rate calculation timing window discrepancies between payment gateway and internal ledger.',
    'AUTO_ADJUST_FX: Apply daily central bank reference rate adjustment within a maximum 2% variance threshold.',
    
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'ISSUE-002',
    'SEPA Settlement Lag',
    'TIMING_DELAY',
    'Missing paired clearing record due to standard SEPA batch banking window delay exceeding standard 5-minute reconciliation window.',
    'EXTEND_HOLD_WINDOW: Automatically re-queue transaction into extended 48-hour SEPA pending buffer before flagging as error.',
    
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'ISSUE-003',
    'Chargeback Refund Race Condition',
    'RACE_CONDITION',
    'Gateway issued a refund event while internal ledger recorded an concurrent chargeback dispute, causing duplicate negative ledger balance.',
    'FLAG_DUPLICATE_REVERSAL: Quarantine ledger entry and trigger manual merchant account balance audit.',
    
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'ISSUE-004',
    'Gateway Ingestion Timeout',
    'NETWORK_TIMEOUT',
    'Payment gateway webhooks failed to deliver payload within timeout window resulting in orphaned pending state.',
    'TRIGGER_GATEWAY_RETRY: Execute API poll to gateway /v1/charges endpoint with X-Idempotency-Key.',
    
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (issue_id) DO NOTHING;