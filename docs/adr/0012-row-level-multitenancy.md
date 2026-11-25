# ADR 0012: Row-level multi-tenancy, no per-tenant partitions

- **Status:** Accepted
- **Date:** 2026-11-26
- **Source:** PROJECT_COMPLETION_TASKS A1.8, open decision §10 #5

## Context
The guide proposed `LIST (tenant_id)` partitioning of tenant tables plus a `TenantProvisioningService`, and a namespace per tenant. Every table already carries `tenant_id` and the unique keys/indexes include it (V1, V5, V10). Kafka uses shared topics keyed by tenant + order (ADR 0010, G1.5).

## Decision
Row-level isolation only: `tenant_id` column on every tenant table, enforced in queries via `TenantContext`. No `LIST (tenant_id)` partitioning, no `TenantProvisioningService`. Shared worker pool; isolated deployments only as a future enterprise option.

## Consequences
- Tenant onboarding needs no DDL (no migration per tenant); `POST /admin/tenants` (G5.9) only inserts config rows.
- Noisy-neighbour and bulk tenant deletion are handled by indexes and time-based partitioning (G2.6), not per-tenant partitions.
- Revisit if a single tenant exceeds roughly 30% of rows, or a contract requires physical isolation. Migrating then means re-creating tables as partitioned and copying.
- Cross-tenant leakage is prevented by code, not the DB; consider Postgres RLS later (E4).
