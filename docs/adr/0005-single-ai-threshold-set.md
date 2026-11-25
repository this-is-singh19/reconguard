# ADR 0005: Single 0.90 / 0.75 AI threshold set

- **Status:** Accepted
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide had two rules: a table with 0.90 / 0.75, and a line "> 0.85 → AUTO_CLASSIFIED". The code uses only 0.90 and 0.75 in `ResolutionSuggestionBuilder`. The string 0.85 does not appear in any code or migration. The engine's unused `FailureDiagnosticEngineService` used 0.80, and that is dead code.

## Decision

One threshold set:

- score ≥ 0.90 → `AUTO_RESOLVED`
- score ≥ 0.75 → `AUTO_CLASSIFIED`
- otherwise → `NEEDS_REVIEW`

0.85 is dropped from the guide.

## Consequences

- The guide's 0.85 line is removed.
- Thresholds are constants today. Per-tenant or per-issue thresholds are not implemented (see ADR 0004).
- Removing the dead 0.80 engine code is a separate cleanup task.
