# ADR 0003: VARCHAR(64) natural ids

- **Status:** Accepted
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide used UUID primary keys and `tenant_id UUID REFERENCES tenants`. The migrations use `VARCHAR(64)` natural keys: `tenants.tenant_id`, `transactions.transaction_id`, `transactions.tenant_id`, `known_issues.issue_id`, `resolution_suggestions.suggestion_id`. Exception: `reconciliation_failures.id` is `UUID` (V2).

## Decision

Keep `VARCHAR(64)` natural ids for tenants, transactions, known issues and suggestions. Update the guide.

## Consequences

- Tenant ids and transaction ids come from the caller and are readable in logs and in Kafka keys.
- No surrogate key lookups are needed.
- `reconciliation_failures.id` stays `UUID` and is the only UUID key. This is inconsistent and can be revisited.
- `transactions.tenant_id` has an FK to `tenants` in V1 (the report's "no FK" note is out of date).
