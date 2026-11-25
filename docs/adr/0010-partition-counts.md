# ADR 0010: Topic partition counts

- **Status:** Accepted – implementation planned (A3.15)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide sets 12 partitions for orders and payments, and 6 for failures. The code defines no `NewTopic` beans and no partition settings in `compose.yaml`. Topics are auto-created with the broker default of 1 partition.

## Decision

Adopt the guide's counts as the target: 12 for the raw transactions topic, 6 for the failures topic. Create them with `NewTopic` beans (A3.15). Partition key is `tenantId:orderId` (G1.1).

## Consequences

- Until A3.15 lands, topics have 1 partition and ordering and parallelism are limited. The guide must mark the counts planned.
- Partition count changes after data exists are hard to undo, so A3.15 should land before real traffic.
