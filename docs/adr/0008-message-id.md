# ADR 0008: `messageId` on the canonical event

- **Status:** Accepted – implementation planned (G1.4)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide's canonical event has a `messageId`. The code does not. `TransactionEvent` (common) has no `messageId` field, and no Java file in the repo sets one. Deduplication today uses `X-Idempotency-Key` (Redis) and `transactionId` (`IdempotencyGuard`).

## Decision

Adopt `messageId` as a field of the canonical event. It is a unique id per message, separate from `transactionId`. Implementation is G1.4.

## Consequences

- `TransactionEvent` changes wire format. Kafka JSON type headers are used, so the change must go with a coordinated release (see CLAUDE.md on record names).
- Until G1.4 lands, the code has no `messageId`. The guide must mark it planned.
