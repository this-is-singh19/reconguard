# Phase 3 - AI Utility Integration and Observability

## Summary
This phase covers the AI diagnostic worker, vector similarity search, resolution suggestion generation, and the observability stack called out in the architecture guide.

## Status
Project view: 6 implemented, 5 partial, 3 not implemented.

### Implemented currently
- [x] Define the `EmbeddingService` interface and implement two backends: OpenAI `text-embedding-3-small` for production and Ollama `nomic-embed-text` for local/air-gapped use, switchable via config with zero code change.
- [x] Implement `FailureContextBuilder` to assemble the structured text sent to the embedding model (amount, currency, gateway, error code, tenant region).
- [x] Implement `VectorSimilaritySearchService` using pgvector cosine similarity with a `LIMIT 3` query against `known_issues`.
- [x] Implement three-tier classification logic in `ResolutionSuggestionBuilder`: `AUTO_RESOLVED` (>= 0.90), `AUTO_CLASSIFIED` (0.75-0.90), `NEEDS_REVIEW` (< 0.75).
- [x] Add `MDC.put("correlationId", ...)` and `MDC.put("tenantId", ...)` in a `MdcFilter` so every request and Kafka listener sets both fields before any logging occurs.
- [x] Add a Logback `MaskingConverter` that replaces card numbers, IBANs, and email addresses with `[MASKED]` before any appender writes.

### Partial
- [ ] Create HNSW indexes on `known_issues.embedding` and `reconciliation_failures.context_embedding` with `m=16, ef_construction=64`.
- [ ] Set `auto_applied = true` on `resolution_suggestions` rows where classification is `AUTO_RESOLVED`.
- [ ] Seed `known_issues` table with at least 50 baseline patterns (Stripe SEPA delays, FX rounding errors, gateway timeouts).
- [ ] Wire Micrometer tracing to the Zipkin instance already in `compose.yaml` and verify spans appear for the full reconcile cycle.
- [ ] Add `recon.match.latency` Timer metric with `tenant` and `status` tags.

### No implementation
- [ ] Add `recon.ai.classification_rate` Gauge metric with a `tenant` tag.
- [ ] Build Grafana dashboards for: reconciliation throughput, consumer group lag, AI classification rate (target >= 0.80), P99 latency (target < 1000ms), Redis idempotency hit rate, PostgreSQL query latency.
- [ ] Add end-to-end Testcontainers test: failure event -> AI classification -> resolution suggestion stored with correct classification status.

## Detailed Tasks
### In place
- Keep the AI worker focused on failure classification, not general-purpose analytics.
- Keep the embedding backend swappable without code changes.
- Keep pgvector and HNSW as the search backbone for known-issue matching.

### Remaining work
- Build the structured failure context passed into embeddings.
- Implement similarity search and classification thresholds from the guide.
- Seed known issues so the classifier has meaningful baseline data.
- Add trace, metric, and dashboard coverage for the full reconciliation-to-AI path.
- Add masking so sensitive values are scrubbed before logs reach appenders.
- Prove the end-to-end flow with an integration test.

## Acceptance Criteria
- The AI worker can classify reconciliation failures using the configured embedding backend.
- Observability covers both the reconcile path and the AI suggestion path.
- Sensitive values are masked and tenant/correlation context is consistently logged.
- The end-to-end test proves the failure -> classification -> suggestion flow.
