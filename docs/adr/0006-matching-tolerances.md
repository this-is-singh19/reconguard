# ADR 0006: Matching tolerances

- **Status:** Accepted (per-tenant tolerances planned, G2.3)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide is inconsistent. One section says ±0.02 and another says ±0.01 for amount. It also says ±120 s for timestamps, which contradicts the 30 s window.

The code:

- `MatchingToleranceConfig` (prefix `reconguard.matching`): `maxAmountDifference` defaults to `0.02`, `maxTimeWindow` defaults to `120 s`.
- `reconciliation-engine/src/main/resources/application.yml` sets `reconguard.matching.max-amount-difference: 0.02` and `max-time-window: 120s`.
- `MatchingEngineService` checks `|a - b| <= maxAmountDifference` and `|t1 - t2| <= maxTimeWindow`.

## Decision

Use amount tolerance **±0.02** and time tolerance **±120 s**, both from `MatchingToleranceConfig` and the engine yml. The ±0.01 value in the guide is dropped. The 30 s figure is the Redis window TTL (35 s), not a matching tolerance, and is a separate decision.

Per-tenant tolerances are not implemented and are planned under G2.3.

## Consequences

- Guide values are updated to ±0.02 and ±120 s.
- Changing a tolerance is a config change, no code change.
- The window TTL and the timestamp tolerance are different values. Docs must not mix them.
