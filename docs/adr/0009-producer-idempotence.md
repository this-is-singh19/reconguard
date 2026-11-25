# ADR 0009: Producer `enable.idempotence=true`

- **Status:** Accepted – implementation planned (A4.8)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide says producer `acks=all` and `enable.idempotence=true`. The ingestion producer in `ingestion-service/src/main/resources/application.yml` sets `acks: all` and `retries: 3`. It does not set `enable.idempotence`. No Java producer config sets it either.

## Decision

Adopt `enable.idempotence=true` for the ingestion producer. The code change is A4.8.

## Consequences

- Retries will not create duplicate records on the raw topic once A4.8 lands.
- Until then the producer relies on consumer-side dedupe (`IdempotencyGuard`). The guide must mark idempotent producer as planned.
