# PROJECT_ROADMAP

## Purpose
Single source of truth for ReconGuard progress, architecture decisions, and next steps.

## Project Objectives
- Build a portfolio-grade Spring Boot system that demonstrates multi-module architecture, event-driven design, Redis idempotency, PostgreSQL persistence, and AI-assisted diagnostics.
- Keep the implementation readable and teachable.
- Stay close to the architecture guide while keeping complexity appropriate for a personal resume project.

## Current Snapshot
- Project task status summary: 29 implemented, 2 partial, 54 not implemented.
- Phase 0 is complete and Phase 1 is functionally in place.
- Current modules: `ingestion-service`, `reconciliation-engine`, `ai-diagnostic-worker`, `common`.
- `common` is the shared contract layer for canonical events, tenant context, and shared security helpers.
- `ingestion-service` is beyond scaffold and already has webhook handling, HMAC/security support, payload normalization, Kafka production, and Redis idempotency.
- `reconciliation-engine` is the active Phase 2 focus and already has the Kafka consumer, Redis idempotency guard, Redis window buffer, matching service, repository layer, and Flyway migration work in progress.
- `ai-diagnostic-worker` is already partially implemented with embedding service abstractions, vector similarity search, suggestion building, metrics, and a direct diagnostic controller endpoint.
- Current infrastructure includes PostgreSQL, Redis, Kafka, Ollama, and Zipkin in `compose.yaml`.
- Current quality gaps are around completeness rather than scaffolding: schema alignment, compile validation, failure publishing, and tests.

## Architecture Summary
- Spring Boot 3.5 and Java 21 with virtual threads for blocking I/O.
- Kafka for event transport.
- Redis for idempotency and window buffering.
- PostgreSQL + Flyway for persistence.
- pgvector + an AI worker for failure classification.
- Spring Security with tenant-aware authentication.
- Micrometer tracing and metrics for observability.

## Comparison With The Architecture Guide
- Already present: multi-module scaffold, base dependency stack, virtual-thread config, and a local compose file.
- Partially present: service boundaries exist only as names.
- Missing: actual service implementations, a `management-api` module, migrations, tests, Docker and Kubernetes manifests, and observability dashboards.
- Deviations: module names and layout differ from the guide; `compose.yaml` uses different images and omits Kafka and Ollama; the root build includes a Gateway dependency even though the guide does not call for a separate gateway service.

## Current Phase
[-] Phase 2 - Reconciliation engine and Redis idempotency, with early Phase 3 work already started

## Phase Plan

### Phase 0 - Repository and architecture alignment
- [x] Rename modules and package layout to match the guide.
- [x] Treat management API concerns within the existing common/module plan for this portfolio scope.
- [x] Wire the `common` module as a shared library for models, tenant context, and security helpers.
- [x] Align `compose.yaml` with the required local services.
- [x] Remove root application behavior and use the root project as build coordination.
- [ ] Verify all modules compile cleanly after Phase 2 package fixes.

### Phase 1 - Data ingestion and Kafka setup
- [x] Implement `TransactionEvent`.
- [x] Implement supporting shared event model types.
- [x] Add Kafka topic and producer configuration.
- [x] Build the webhook ingestion API and HMAC verification.
- [ ] Add CDC/JDBC source configuration for order events.
- [ ] Add Testcontainers coverage for Kafka round-trips.

### Phase 2 - Reconciliation engine and Redis idempotency
- [x] Add reconciliation module dependencies for Kafka, Redis, JPA, PostgreSQL, and Flyway.
- [x] Implement initial Kafka consumer.
- [x] Implement initial Redis idempotency guard with `SETNX` and TTL.
- [x] Implement initial Redis window buffer with 35-second TTL.
- [x] Add the matching engine.
- [x] Create Flyway migration for tenants, transactions, and failures.
- [ ] Verify `TransactionRecord` package and imports are aligned with the current entity and repository layout.
- [ ] Align `TransactionRecord` entity fields with the Flyway `transactions` schema.
- [x] Replace exact amount comparison with configured tolerance comparison.
- [x] Add timestamp tolerance matching using `TransactionEvent.timestamp`.
- [x] Persist unresolved events during matching flow, while keeping the final unresolved/failure policy explicit.
- [ ] Add failure publishing for unmatched/failed events.
- [ ] Add unit tests for matching outcomes.
- [ ] Add integration tests for match, duplicate, tenant isolation, and timeout/failure scenarios.

### Phase 2.5 - Ingestion and reconciliation gaps
- [ ] Add `PayloadNormalizationService` to map gateway-specific schemas to the canonical `TransactionEvent` before publishing to Kafka.
- [ ] Enforce `X-Idempotency-Key` header on the webhook endpoint and return a cached `202` response for duplicate keys.
- [ ] Verify the webhook controller returns the correct `202 Accepted` response shape (`ingestion_id`, `received_at`).
- [ ] Add RFC 7807 `application/problem+json` error responses to the ingestion endpoint, including HMAC failures.
- [ ] Add FX rate normalization before amount tolerance comparison — cache the rate and refresh every 60 seconds.
- [ ] Wire `TenantContextHolder` propagation inside the Kafka consumer so all repository calls are automatically tenant-scoped.
- [ ] Configure `DefaultErrorHandler` with exponential back-off (1s / 2s / 4s / 8s, max 3 retries) and a dead-letter topic `recon.dlq.{tenantId}` per consumer.
- [ ] Resolve the `correlation_key` vs guide schema conflict — decide whether the `transactions` table uses the current migration columns or the guide's columns (`order_id`, `gateway_transaction_id`, `amount`, `currency`, `order_ts`, `payment_ts`, `status`) and rewrite the migration accordingly.
- [ ] Add Flyway migrations for `known_issues` (V4) and `resolution_suggestions` (V5) so Phase 3 has a schema foundation to build on.
- [ ] Add a `TenantProvisioningService` that creates the `LIST (tenant_id)` partition for a new tenant at runtime rather than in Flyway.

### Phase 3 - AI utility integration and observability
- [ ] Define the `EmbeddingService` interface and implement two backends: OpenAI `text-embedding-3-small` for production and Ollama `nomic-embed-text` for local/air-gapped use, switchable via config with zero code change.
- [ ] Implement `FailureContextBuilder` to assemble the structured text sent to the embedding model (amount, currency, gateway, error code, tenant region).
- [ ] Implement `VectorSimilaritySearchService` using pgvector cosine similarity with a `LIMIT 3` query against `known_issues`.
- [ ] Create HNSW indexes on `known_issues.embedding` and `reconciliation_failures.context_embedding` with `m=16, ef_construction=64`.
- [ ] Implement three-tier classification logic in `ResolutionSuggestionBuilder`: `AUTO_RESOLVED` (>= 0.90), `AUTO_CLASSIFIED` (0.75–0.90), `NEEDS_REVIEW` (< 0.75).
- [ ] Set `auto_applied = true` on `resolution_suggestions` rows where classification is `AUTO_RESOLVED`.
- [ ] Seed `known_issues` table with at least 50 baseline patterns (Stripe SEPA delays, FX rounding errors, gateway timeouts).
- [ ] Add `MDC.put("correlationId", ...)` and `MDC.put("tenantId", ...)` in a `MdcFilter` so every request and Kafka listener sets both fields before any logging occurs.
- [ ] Add a Logback `MaskingConverter` that replaces card numbers, IBANs, and email addresses with `[MASKED]` before any appender writes.
- [ ] Wire Micrometer tracing to the Zipkin instance already in `compose.yaml` and verify spans appear for the full reconcile cycle.
- [ ] Add `recon.match.latency` Timer metric with `tenant` and `status` tags.
- [ ] Add `recon.ai.classification_rate` Gauge metric with a `tenant` tag.
- [ ] Build Grafana dashboards for: reconciliation throughput, consumer group lag, AI classification rate (target >= 0.80), P99 latency (target < 1000ms), Redis idempotency hit rate, PostgreSQL query latency.
- [ ] Add end-to-end Testcontainers test: failure event → AI classification → resolution suggestion stored with correct classification status.

### Phase 4 - Hardening and portfolio polish
- [ ] Implement Redis sliding-window rate limiting per `{tenantId}:{endpoint}` with per-tier limits (FREE: 100 req/min, PRO: 1,000 req/min, ENTERPRISE: configurable).
- [ ] Add `Deprecation: true` and `Sunset: <date>` response headers via a `DeprecationService` for any versioned endpoints being retired.
- [ ] Verify TLS is enforced on all PostgreSQL and Redis connections in non-local profiles.
- [ ] Implement the Management API module with the six REST endpoints: list reconciliations (paginated + filtered), get by ID, resolve, get metrics summary, create/list/delete known issues.
- [ ] Enforce RFC 7807 `application/problem+json` on all Management API error responses.
- [ ] Add role-based access control: `RECON_VIEWER` for read endpoints, `RECON_ADMIN` for known-issue write endpoints.
- [ ] Write a concise runbook covering: how to start local infrastructure, how to run tests, how to trigger a reconciliation failure and observe the AI suggestion, and what each module does.
- [ ] Write architecture notes summarising the key design decisions for the resume demo (why virtual threads, why HNSW, why blocking MVC).

### Phase 5 - Deployment and CI/CD
- [ ] Write a `Jenkinsfile` with stages: Build, Unit Tests, OWASP Dependency Check, Trivy image scan, Integration Tests, Deploy to Staging, k6 performance test, Deploy to Production (manual approval gate).
- [ ] Write `Dockerfile` for each module using a multi-stage build (`eclipse-temurin:21-jdk` builder → `eclipse-temurin:21-jre` runtime).
- [ ] Write a Helm chart with templates for each service Deployment, Service, and ConfigMap.
- [ ] Add a K8s `HorizontalPodAutoscaler` for the reconciliation engine using `kafka_consumer_group_lag` as the external metric (min 2 replicas, max 20, target lag 1,000).
- [ ] Add one K8s Namespace per tenant for resource isolation.
- [ ] Write a k6 performance test that sustains 1,000 events/sec for 5 minutes and asserts P99 reconciliation latency < 1s and zero duplicate MATCHED records.
- [ ] Add a `docker-compose.override.yml` for any local-only services or port mappings that should not appear in the production compose file.

## Completed Tasks
- [x] Reviewed the repo structure and module layout.
- [x] Extracted the PDF architecture guide text.
- [x] Compared the scaffold with the guide.
- [x] Identified missing components, deviations, and risks.
- [x] Created this roadmap.
- [x] Completed initial module alignment for Phase 0.
- [x] Completed initial common event model for Phase 1.
- [x] Completed initial reconciliation engine scaffold for Phase 2.
- [x] Implemented the ingestion service’s webhook, HMAC, normalization, producer, and Redis idempotency flow.
- [x] Implemented the reconciliation engine’s consumer, idempotency, window buffer, matching logic, and repository path.
- [x] Implemented the AI worker’s embedding abstraction, similarity search, and suggestion-building flow.

## In Progress
- [-] Phase 2 - Reconciliation engine and Redis idempotency.
- [-] Phase 3 groundwork in the AI diagnostic worker.

## Pending Tasks
- [ ] Finish Phase 2 entity/schema validation.
- [ ] Finish Phase 2 failure publishing.
- [ ] Finish Phase 2 tests.
- [ ] Implement Phase 2.5 ingestion and reconciliation gaps.
- [ ] Implement Phase 3 AI integration and observability (including Zipkin wiring).
- [ ] Implement Phase 4 hardening, Management API, and portfolio polish.
- [ ] Implement Phase 5 deployment and CI/CD.

## Notes And Learning Points
- The architecture guide intentionally favors blocking Spring MVC plus virtual threads over WebFlux. That is worth understanding before writing service code.
- The shared `common` module should hold cross-cutting types and context helpers, not application entry points.
- Module names matter because they make service boundaries visible in the codebase and in Gradle.
- A multi-module build is easier to reason about when the root project coordinates subprojects instead of also pretending to be an application.
- Matching logic should be kept as pure business logic where possible. Kafka, Redis, and database concerns should orchestrate around it instead of being mixed into the matching rules.
- Exact amount equality is usually too strict for financial reconciliation. The guide expects configurable tolerance.
- Schema/entity alignment is foundational: JPA can compile only when packages are correct, and it can run only when entity mappings match Flyway migrations.
- `DiagnosticController` is a standalone diagnostic surface in the AI worker and not part of the guide’s core Kafka-driven failure pipeline.

## Architecture Decisions
- ADR-001: Use a service-per-module Gradle layout with a shared `common` module.
- ADR-002: Use Kafka for event transport instead of synchronous service-to-service calls.
- ADR-003: Use Redis for idempotency and short-lived reconciliation state.
- ADR-004: Use PostgreSQL with Flyway migrations for durable business data.
- ADR-005: Use blocking Spring MVC plus virtual threads for simpler code and a more teachable concurrency model.
- ADR-006: Keep tenant isolation enforced in the application and repository layers, not only at the controller layer.
- ADR-007: Use HNSW over IVFFlat for pgvector indexes because HNSW does not require a training step and handles continuous high-volume inserts more predictably.
- ADR-008: Use URI versioning (`/api/v1/`) over header versioning because it is more visible in logs, curl commands, and API gateways.
- ADR-009: Define `EmbeddingService` as an interface with OpenAI as the production backend and Ollama as the local/air-gapped fallback, switchable via Spring profile with zero code change.

## Questions And Risks
- Should the `transactions` table model one combined reconciliation result, or should each event leg be stored as one `TransactionRecord` row?
- Should unmatched buffered events be persisted immediately as `UNRESOLVED`, or only persisted after timeout/failure?
- Should amount and timestamp tolerances be global config for now, or tenant-specific config in a later phase?
- Do we want a separate API gateway at all, or should synchronous management concerns stay inside the common/module plan for this portfolio scope?
- How much of the deployment layer belongs in the portfolio version versus the learning version?
- What happens to Redis window state if a reconciliation-engine pod crashes mid-window? The guide relies on Kafka offset not being committed until the DB write succeeds, but in-flight window entries in Redis have a TTL and may hold partially-seen events when the pod restarts. Need to decide whether this is acceptable for portfolio scope or requires a recovery strategy.
- HNSW index memory footprint as `known_issues` grows beyond 1M rows — monitor and plan for an offline index rebuild if build time or memory becomes unmanageable.

## Latest Phase 2 Review - 2026-07-19
- Last review note: `TransactionRecord` package/import alignment should still be verified with a compile check, since the roadmap was written before the current code shape was sampled.
- Matching logic now uses tolerance-based amount and timestamp comparison, but the persistence schema still needs a final pass against the Flyway migration.
- Next task: verify compile/runtime alignment, then finish failure publishing and tests so Phase 2 can be closed cleanly.
