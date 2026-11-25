# Phase 2 - Reconciliation Engine and Redis Idempotency

## Summary
This phase covers the reconciliation engine described in the architecture guide: Kafka consumption, Redis idempotency, window buffering, matching, durable persistence, and failure publishing.

## Status
Project view: 10 implemented, 2 partial, 2 not implemented.

### Implemented currently
- [x] Add reconciliation module dependencies for Kafka, Redis, JPA, PostgreSQL, and Flyway.
- [~] Implement initial Kafka consumer. → 0F.4
- [~] Implement initial Redis idempotency guard with `SETNX` and TTL. → 0F.4
- [~] Implement initial Redis window buffer with 35-second TTL. → 0F.4
- [~] Add the matching engine. → 0F.4
- [x] Create Flyway migration for tenants, transactions, and failures.
- [~] Replace exact amount comparison with configured tolerance comparison. → 0F.11
- [~] Add timestamp tolerance matching using `TransactionEvent.timestamp`. → 0F.11
- [~] Persist unresolved events during matching flow, while keeping the final unresolved/failure policy explicit. → 0F.4
- [x] Add failure publishing for unmatched/failed events.

### Partial
- [ ] Verify `TransactionRecord` package and imports are aligned with the current entity and repository layout.
- [ ] Align `TransactionRecord` entity fields with the Flyway `transactions` schema.

### No implementation
- [ ] Add unit tests for matching outcomes.
- [ ] Add integration tests for match, duplicate, tenant isolation, and timeout/failure scenarios.

## Detailed Tasks
### In place
- Kafka intake should remain centered on `tx.orders.{tenantId}` and `tx.payments.{tenantId}` consumption.
- Redis SETNX should continue to gate duplicate processing before any repository or matching work.
- The 30-second window and short-lived Redis buffer should remain the source of truth for short-gap counterpart matching.
- Matching should continue to use tolerance-based amount and timestamp comparison rather than exact equality.
- Unresolved legs should still be persisted so the failure path can be explicit rather than implicit.

### Remaining work
- Confirm the persistence model and repository path are aligned with the architecture guide's transaction lifecycle.
- Wire failure publication for unmatched and failed records into the reconciliation flow.
- Add tests that cover:
  - matched pair persistence
  - duplicate message suppression
  - tenant isolation
  - timeout-driven failure handling
  - unresolved-to-failed transition behavior

## Acceptance Criteria
- Reconciliation processing follows the guide's window-matching and idempotency rules.
- Failure events are published when no match is found within the allowed window.
- Tests prove the happy path, duplicate path, and failure path behave as intended.
