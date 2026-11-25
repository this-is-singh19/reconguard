# ADR 0011: Ingestion deduplicates on `X-Idempotency-Key` only

- **Status:** Accepted
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT; task 0F.33

## Context
Ingestion dedups on `(tenant, X-Idempotency-Key)` via Redis SETNX (24h TTL). The same `transactionId` sent with a new key is republished.

## Decision
Keep key-only dedup at ingestion. Semantic dedup by `(tenant, transactionId)` stays in the engine (`IdempotencyGuard` plus the `transactions` PK / unique constraint). The key is released when Kafka publish fails so clients can retry.

## Consequences
- Duplicate Kafka messages are possible; the engine is the source of truth for exactly-once effects.
- A later `messageId` (ADR 0008, G1.4) can tighten this.
