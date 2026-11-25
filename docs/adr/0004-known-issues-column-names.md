# ADR 0004: `known_issues` column names

- **Status:** Accepted (threshold and tenant columns planned, G1.9)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide had `known_issues(id, title, resolution_template, confidence_threshold)`. The migrations (V4, V7) create:

`issue_id VARCHAR(64) PK`, `pattern_name VARCHAR(128)`, `category VARCHAR(64)`, `description`, `recommended_action TEXT`, `created_at`, `updated_at`, `embedding` (vector, HNSW cosine index).

There is no `confidence_threshold` and no `tenant_id` column.

## Decision

Keep the code column names. Update the guide to match them. Add `tenant_id` and `confidence_threshold` only when G1.9 is implemented. Until then they are planned, not present.

## Consequences

- The guide's `id`, `title` and `resolution_template` are replaced by `issue_id`, `pattern_name` and `recommended_action`.
- The AI worker's `KnownIssue` entity and vector search query use the current names.
- Per-issue thresholds are not used today. The single threshold set in ADR 0005 applies.
