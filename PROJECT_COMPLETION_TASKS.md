# ReconGuard – Completion Task List

_Generated 2026-11-25 from a code audit of the working tree (including a `./gradlew compileJava compileTestJava` run), not just from the existing roadmap docs._

Legend: **P0** = blocks the system from working end-to-end · **P1** = needed for correctness/completeness · **P2** = hardening/polish · `[ ]` open · `[~]` partly done

---

## 1. Current Status (audited)

| Module | State | Summary |
|---|---|---|
| `common` | Mostly done | `TransactionEvent`, `MonetaryAmount`, `TenantContext`, `MdcFilter`, `MaskingConverter`, `TransactionFailureEvent`. Leftover `Main.java`; tenant defaults to `"DEFAULT_GLOBAL"` when header absent. |
| `ingestion-service` | ~70% | Webhook endpoint, HMAC interceptor, Redis idempotency key, normalization, Kafka producer all exist. Gateway-specific payloads (`GatewayPayload`, `StripeWebhookPayload`) are **not wired** to the controller. Has 2 test classes. |
| `reconciliation-engine` | ~60% | Consumer, idempotency guard, Redis window buffer, matching with tolerances, DLQ config, sweeper → failure publisher exist. **Main code compiles; test code does not.** Migrations are inconsistent (see P0). |
| `ai-diagnostic-worker` | ~50% | Embedding abstraction (Ollama), context builder, pgvector search, 3-tier suggestion builder, metrics exist. **Not connected to the reconciliation output** and has duplicated/broken pieces (see P0). |
| Infra (`compose.yaml`) | Partial | Postgres(pgvector), Redis, Kafka (KRaft), Ollama, Zipkin. **No Prometheus, no Grafana, no Kafka UI, no app containers.** |
| Observability | ~25% | One Grafana dashboard JSON (AI worker metrics only). Tracing deps only in ingestion. Recon engine has no actuator/Prometheus. |
| Tests | ~15% | 741 lines total; the reconciliation-engine test source set currently **fails to compile** (4 errors, see report §6), so none of its tests run. |
| Deployment / CI | 0% | No Dockerfiles, Jenkinsfile, Helm, k6. |
| Management API / RBAC / rate limiting | 0% | No Spring Security anywhere. |

**Existing docs are partly stale/optimistic.** `PROJECT_ROADMAP.md` and `phase-*.md` mark items `[x]` that do not actually work end-to-end (e.g. "failure publishing" – the topic/payload published by the engine is not what the AI worker consumes). Phase 0 (tasks 0.1–0.3) audits and corrects them.

**Section map:** §1b Phase 0 discrepancy audit (first) · §2 Phase A pipeline fixes · §3 B tests · §4 C observability · §5 D security/API · §6 E deployment · §7 F docs · §7b G architecture improvements (from the architecture review) · §8 execution order · §9 definition of done · §10 open decisions.

---

## 1b. Phase 0 – Discrepancy Audit (do this FIRST, before any other task)

Goal: establish the ground truth. Docs, guide, code, schema, config and tests have drifted apart; every later task depends on knowing which one is right. Output of this phase is a short `docs/DISCREPANCY_REPORT.md` (table: item · source A says · source B says · decision · owner task) and a corrected baseline.

**Status (2026-11-25):** audit 0.1–0.9 done → findings in [`docs/DISCREPANCY_REPORT.md`](docs/DISCREPANCY_REPORT.md). Fixes are listed below as **0F.\*** (each points at the existing Phase A–G task when one already covers it, otherwise it is new work). 0.10 smoke test done (report §9: every engine insert fails on `created_at`, forged signatures accepted). 0.11 done (2026-11-25) – see **0.11 changelog** below; baseline tagged `baseline-audit`.

### 0A. Audit tasks
- [x] **0.1 Guide vs code.** Compare `architecture` / `reconguard_architecture.pdf` section by section with the implementation (topics, endpoint paths, `TransactionEvent` fields, schema columns/types, thresholds, window/tolerance values, partition counts, Spring Boot version 3.3 vs 3.5, LangChain4j vs Spring AI). For each deviation record **adopt guide / keep code / update guide**. Known examples to start from: `/api/v1/webhooks/{tenantId}/payment` vs `/api/v1/webhooks/transactions`; UUID ids vs `VARCHAR(64)`; `X-Webhook-Signature: sha256=…` vs `X-Signature`; `tx.orders.{tenantId}` vs `reconguard.transactions.raw`; `recon.failures.{tenantId}` vs `reconguard.transactions.failures`; 0.85 vs 0.75/0.90 thresholds; `known_issues` column names (`title`, `resolution_template`) vs (`pattern_name`, `recommended_action`); `resolution_suggestions.failure_id` vs `transaction_id`.
- [x] **0.2 Roadmap/phase docs vs code.** For every `[x]` in `PROJECT_ROADMAP.md`, `phase-2-reconciliation.md`, `phase-2-5-ingestion-gaps.md`, `phase-3-ai-observability.md`, verify by reading code and, where possible, running it. Flag false positives (e.g. "failure publishing" done but not consumable by the AI worker; "Add failure publishing", "HNSW indexes", "50 seeds" done but unusable). Correct the checkboxes or mark them with this file's task IDs.
- [x] **0.3 Other markdown docs vs code.** Check `KAFKA_IMPLEMENTATION.md`, `REDIS_IMPLEMENTATION.md`, `AI_INTEGRATION_AND_FLOW.md`, `MISCELLANEOUS_IMPLEMENTATION.md`, `INTERVIEW_GUIDE*.md` for statements that no longer match the code (class names, flows, topics, thresholds). Interview material that states something untrue is a liability – fix or remove.
- [x] **0.4 Schema discrepancies.** Spin up a clean Postgres (pgvector) and run all migrations in order; record every failure/warning. Compare resulting schema with JPA entities (`ddl-auto: validate` in a throwaway run) and with native SQL in repositories (`KnownIssueRepository`, `TransactionRepository`). Compare against the guide's schema.
- [x] **0.5 Config discrepancies.** Diff property names read by code (`@Value`, `@ConfigurationProperties`, `@ConditionalOnProperty`) vs keys in each `application.yml`/`compose.yaml` (known miss: `reconguard.matching.*` vs `reconguard.topics.matching.*`). Check ports (8080/8081/8082), group IDs (three different AI-worker group IDs), topic names hard-coded in annotations, Kafka listener names (`PLAINTEXT` vs `TRACK`), trusted packages for JSON deserialization.
- [x] **0.6 Cross-module contract discrepancies.** Verify producer/consumer pairs agree on topic, key, headers, payload class and JSON shape: ingestion → engine (`TransactionEvent`), engine → AI worker (`FailurePayload` vs `TransactionFailureEvent`), engine → DLQ (`X-Tenant-Id` header never set). Record as a contract table in the report.
- [x] **0.7 Build/dependency discrepancies.** Run `./gradlew dependencies` per module: unused/missing starters (OpenAI starter absent, Prometheus registry absent, tracing only in ingestion, actuator absent in engine), mixed version pinning in `common`, `project(':ai-diagnostic-worker')` dependency from the engine, `bootJar` vs plain `jar` for library modules, H2 test dependency vs Postgres-only SQL. Run the full build (`./gradlew clean build`) and capture the baseline of failing tasks/tests.
- [x] **0.8 Dead/duplicate/leftover code inventory.** List unused classes and duplicates (`TransactionMatchingEngine`/`MatchResult` unused, two sweepers, two AI listeners, `DiagnosticWorkerService` vs `FailureDiagnosticEngineService`, `WindowEvictionListener`, `Main.java`, `TestTransactionController`, `FAIL-TEST` hook, `tmp/`, `test.log`, commented-out code blocks, `HELP.md`). Decide delete / keep / merge per item.
- [x] **0.9 Repo/VCS state.** 91 files changed and mostly uncommitted on `master` (last commit predates the work), untracked Gradle wrapper, generated files and logs; check `.gitignore` coverage (`*.log`, `tmp/`, `.idea/`, `.mcp.json`, `build/`) and that no secrets are tracked (DB password, default webhook secret).
- [x] **0.10 Runtime smoke test of the "current truth".** With infra up (`docker compose up`), start all three apps and push one matched pair, one lone leg, one duplicate and one malformed message through; record what actually happens at each hop (logs, DB rows, topics, metrics). This establishes the real baseline and confirms/refutes the findings in sections 2–3 of this document.
- [x] **0.11 Reconcile this task list.** Re-read this file against the report; adjust priorities, delete tasks that turn out to be non-issues, add tasks for newly found drift. Then freeze a baseline commit/tag (`baseline-audit`) before starting Phase A.

**0.11 changelog (reconciliation against `docs/DISCREPANCY_REPORT.md`):**
- Fixed stale facts: engine test compile errors 5 → 4; "0.11 pending" status.
- Priority changes: A3.11 P1 → **P0** (created_at, = 0F.4); A2.6 P0 → P1 (cosmetic metric/tag fix); A4.7 re-scoped (real root cause = wrapper type mismatch = 0F.2, not empty body); 0F.19 folded into 0F.22 (no separate work).
- Non-issues found: none deleted. Every Phase A–G task survived the audit; many were *confirmed* by running code (report X1–X9).
- New tasks added by audit: 0F.2, 0F.10, 0F.12, 0F.15, 0F.16, 0F.18, 0F.21, 0F.28–0F.33 (0F.27 moved to hygiene group).
- Execution order (§8) now starts with 0F.1–0F.9 / 0F.28 (clean-DB boot, green build) – most *are* Phase A tasks, so no duplicate work.
- Baseline frozen: tag `baseline-audit` on branch `baseline-audit` (all pending work committed as-is; `tmp/`, `*.log`, `.mcp.json` now in .gitignore, `tmp/V7…sql` unstaged = 0F.21 partial). Nothing is fixed by that commit – it only preserves the audited state.

### 0F. Discrepancy fixes (from `docs/DISCREPANCY_REPORT.md`)
Order: 0F.1–0F.9 make the pipeline boot and the build green (do first); 0F.10–0F.20 config/contract drift; 0F.21–0F.27 hygiene & docs. "→ Ax.y" = do it as that task (scope noted here), "NEW" = not covered elsewhere.

**Blockers (P0)**
- [ ] **0F.1 (P0) V5 FK fails on clean DB** → A1.1. Also fold A1.2 extras found in audit: V2's `transactions` FK to `tenants` is silently lost, duplicate index `idx_transactions_order_tenant` ≡ `idx_tx_tenant_order_status`.
- [ ] **0F.2 (P0) NEW – HMAC is never verified.** `HmacSecurityInterceptor` only verifies when `request instanceof ContentCachingRequestWrapper`, but `RequestCachingFilter` wraps with its own `CachedBodyRequestWrapper` → verification skipped, any non-blank `X-Signature` accepted. Fix: interceptor reads the cached bytes from the real wrapper type (expose `getCachedBody()`), **fail closed** (reject when body can't be read), add test with valid / tampered / missing signature over a real HTTP request (current `WebhookControllerTest` passes without exercising this). Overlaps A4.7.
- [ ] **0F.3 (P0) Engine → AI contract** → A2.1 + A2.2 + A2.3: one topic, one payload (`TransactionFailureEvent`), drop `FailurePayload`, read topic from `reconguard.topics.failed-transactions` (key exists in engine yml but is unused by `FailurePublisher`), JSON consumer factory in AI worker, single listener.
- [ ] **0F.4 (P0) `created_at` NULL on every insert** → A3.11, raised to P0 (`@CreationTimestamp`/`@PrePersist`, `@UpdateTimestamp`; set precision/scale `(18,4)` on `amount`).
- [ ] **0F.5 (P0) Embedding dimension 1536 vs 768** → A1.4 (decide §10.1 first).
- [ ] **0F.6 (P0) Constant seed embeddings (similarity = 1.0 for all)** → A1.5.
- [ ] **0F.7 (P0) Engine test sources don't compile** → B1.
- [ ] **0F.8 (P0) AI worker IT fails: `missing table [known_issues]`** → A1.6 + B5: tests must run the real migrations (shared migration module / Flyway in test profile).
- [ ] **0F.9 (P0) AI beans run inside the engine** → A2.5. Audit specifics: engine loads both AI listeners (with the engine's `TransactionEvent`-typed container factory and the engine's group id for `DiagnosticEventListener`), and has no `spring.ai.*` config, so it would embed with Spring AI's default model, not `nomic-embed-text`.

**Config / contract drift (P1)**
- [ ] **0F.10 (P1) NEW – Virtual threads are not enabled.** `spring.threads.virtual.enabled` lives only in `common/src/main/resources/application.yml`, which each app's own `application.yml` shadows. Move the flag into each app's yml (or an `EnvironmentPostProcessor` in common), delete common's yml, verify via thread names in logs; add consumer virtual-thread executor if the guide's design is kept.
- [ ] **0F.11 (P1) Matching tolerance properties ignored** → A3.9 (both prefix *and* field names differ: code `max-amount-difference`/`max-time-window` vs yml `amount-tolerance`/`timestamp-tolerance`). Add a binding test.
- [ ] **0F.12 (P1) NEW – Engine Kafka config split-brain.** Custom `ConsumerFactory<String, TransactionEvent>` overrides yml (`spring.json.trusted.packages`, value-deserializer are dead config), and is the default factory for *all* listeners (DLQ, AI). Pick one source (yml or Java), name factories per payload type, align trusted packages, remove dead yml keys.
- [ ] **0F.13 (P1) DLQ wiring** → A3.1 + A3.3 (consumer swallows exceptions; `recon.dlq.default` vs `reconguard.transactions.raw.DLQ`; docs say "3 retries" but config is `maxElapsedTime 15s` – make retry count explicit).
- [ ] **0F.14 (P1) Consumer group IDs** – one group per logical consumer, from config: engine (`reconguard-reconciliation-group`, remove fallback `reconciliation-service-group`), DLQ, single AI group (delete `reconguard-ai-diagnostic-group` path with A2.3).
- [ ] **0F.15 (P1) NEW – Hard-coded webhook secret default** (`super-secret-webhook-key-123` in `@Value` default) → remove default, fail fast when unset; long-term A4.1/G5.1.
- [ ] **0F.16 (P1) NEW – Spring AI / Boot compatibility.** Spring AI 1.0.0 on Boot 3.5.13 – bump to the latest 1.0.x/1.1.x that supports Boot 3.5 and record the pair; add OpenAI starter or delete `OpenAiEmbeddingService` (A2.10).
- [ ] **0F.17 (P1) Actuator/metrics config** → C1, plus: replace deprecated keys (`management.endpoint.prometheus.enabled` → `access`, `metrics.distribution.sla` → `slo`); unify tag names (`tenant` vs `tenant_id`); `incrementSuggestionOutcome` passes `matchedIssueId` as the `category` tag (wrong + high cardinality) → A2.6.
- [ ] **0F.18 (P1) NEW – compose.yaml typo** `KAFKA_TRANSACTION_STATE_LOG_REREPLICATION_FACTOR` → `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` (otherwise defaults to 3 on a 1-broker cluster and transactional producers fail). Rename `TRACK` listener (E2).
- [ ] **0F.19 (P1) Duplicate JPA entity on `transactions`** – delete `model.Transaction` + `TransactionId` (composite id doesn't match DB PK). _Folded into 0F.22; no separate work._
- [ ] **0F.20 (P1) Guide decisions** – record in ADRs / update `architecture` per report §2: Boot 3.5, Spring AI (not LangChain4j), VARCHAR ids, `known_issues` column names, single 0.90/0.75 threshold set (drop 0.85), amount tolerance (guide says both ±0.01 and ±0.02), header `X-Webhook-Signature: sha256=` (adopt, G5.1), `messageId` (adopt, G1.4), `enable.idempotence` (adopt, A4.8), partition counts (A3.15). → G1.9 / G7 / F3.

**Hygiene & docs (P1/P2)**
- [~] **0F.21 (P1) NEW – (.gitignore entries + unstage done in 0.11; deleting `tmp/` files and committing wrapper remain) Unstage `tmp/V7__add_id_to_transactions.sql`** (currently staged, collides with real V7) and delete `tmp/`; add `*.log`, `tmp/`, `.mcp.json` to `.gitignore`; commit Gradle wrapper → F5/E6.
- [ ] **0F.22 (P1) Dead code removal** per report §7 → A2.3, A2.4, A2.5, A3.6, A3.8, A3.10, A3.14 plus NEW: `SecurityUserContext`, `model.Transaction`/`TransactionId`, `common/Main.java`, `common/application.yml`, commented-out blocks in `TestTransactionController`, duplicate commented `spring-kafka-test` line and misleading Boot comment in build files.
- [ ] **0F.23 (P2) Build version drift** → E6 (common pins starter-web/validation 3.5.13 + Lombok 1.18.30 vs BOM 1.18.44; assertj pinned only in ingestion; `common` shouldn't depend on starter-web); replace `@MockBean` (5 sites) with `@MockitoBean`.
- [ ] **0F.24 (P1) Un-tick false roadmap checkboxes.** Mark the 14 PARTIAL items in `PROJECT_ROADMAP.md` / `phase-*.md` (report §10) as `[~]` with the fixing task ID.
- [ ] **0F.25 (P1) Fix HIGH doc claims** (report §10): AI flow works, virtual threads in use, Spring Security/tenant-aware auth – in `KAFKA_IMPLEMENTATION.md`, `AI_INTEGRATION_AND_FLOW.md`, all three `INTERVIEW_GUIDE*.md`. Then the MED ones (DLQ, evict→retry, gateway normalization, Ollama/OpenAI interchangeable, Zipkin tracing, MDC on listeners, configurable tolerance). Rewrite as "current state / planned" until the code catches up.
- [ ] **0F.26 (P2) Doc-vs-code gaps worth implementing instead of un-claiming**: `FailureContextBuilder` gateway / error code / region (→ G4.2), MDC for Kafka listeners (→ C5), cached duplicate response (→ A4.5), `problem+json` for missing signature (→ A4.6).
- [ ] **0F.27 (P2) Correct CLAUDE.md**: `/actuator/prometheus` isn't served (no registry), engine yml trusts only `io.reconguard.common.model`, HMAC caveat (0F.2), virtual threads caveat (0F.10). Update once the matching fixes land.

**Found by the smoke test (0.10)**
- [ ] **0F.28 (P0) NEW – Dev DB ≠ clean DB.** Local `pgdata` has V1–V8 marked successful (built from older file versions), so the engine boots locally while a clean DB fails at V5. After 0F.1/A1.2 rewrite the migrations, recreate the volume (`docker compose down -v`, with care) and add a CI/Testcontainers check that runs all migrations on an empty DB.
- [ ] **0F.29 (P1) Invalid JSON → 500** instead of 400 problem+json → A4.10 (`HttpMessageNotReadableException` handler).
- [ ] **0F.30 (P1) Engine `/actuator/health` 503 DOWN.** Enable `show-details` and confirm the cause (likely `AiWorkerHealthIndicator` pulled in via the `:ai-diagnostic-worker` dependency, using Spring AI's default model) → resolves with 0F.9/A2.5; then add the engine's own actuator (C1).
- [ ] **0F.31 (P1) Persist failure loses the event.** On the `created_at` error the consumer evicts the claim and *commits the offset* – the event is gone, no retry/DLQ. Confirms A3.1 urgency; include a test that a DB failure ends in retry → DLQ.
- [ ] **0F.32 (P2) Misleading `WindowEvictionListener` log** ("Leg remains UNRESOLVED in DB") printed even when the insert failed → delete with A3.8.
- [ ] **0F.33 (P2) Ingestion dedups only on `X-Idempotency-Key`** – same `transactionId` with a new key is re-published. Decide whether ingestion also dedups on `(tenant, transactionId)` or leaves it to the engine/DB (G1.4).


**Exit criteria:** every discrepancy has a recorded decision; the engine/AI/ingestion contract table exists; the baseline build + smoke-test results are documented; the roadmap/phase docs no longer claim things that are untrue.

---

## 2. Phase A – Make the pipeline actually work end-to-end (P0)

Goal: `POST webhook → Kafka → reconcile → (unmatched) failure event → AI worker → resolution_suggestions row`, from a clean database, with one command.

### A1. Fix the database schema (Flyway)
- [ ] **A1.1 (P0) `V5` migration fails on a fresh DB.** `resolution_suggestions` has an FK to `transactions(tenant_id, transaction_id)`, but `transactions` only has `PRIMARY KEY (transaction_id)` – Postgres requires a unique constraint on the referenced column pair. Fix: either add `UNIQUE (tenant_id, transaction_id)` to `transactions`, or drop the composite FK and reference `transaction_id` only.
- [ ] **A1.2 (P0) Duplicate/contradictory `transactions` DDL.** `V1__create_transactions_table.sql` and `V2__init_reconciliation_schema.sql` both `CREATE TABLE IF NOT EXISTS transactions`; because V1 runs first, V2's version (with the `tenants` FK and `NOT NULL` status) is silently skipped. Consolidate into one authoritative definition. Because V1 is untracked/new, rewrite history cleanly (no applied-checksum problem yet) – decide before anyone else has applied them.
- [ ] **A1.3 (P0) Missing `V3`** (gap in version numbers) and a stray `tmp/V7__add_id_to_transactions.sql` that conflicts with the real `V7`. Delete `tmp/`, renumber or add a no-op/real `V3`.
- [ ] **A1.4 (P0) Embedding dimension mismatch.** Tables use `vector(1536)` (OpenAI size) but the configured model `nomic-embed-text` outputs **768** dims → every insert/query will throw. Pick one: change columns to `vector(768)` for Ollama (and make dimension a documented config), or switch the default model. Add a startup check that compares `embeddingModel` dimension with the column dimension.
- [ ] **A1.5 (P0) Seed embeddings are fake.** V6/V8 fill each `known_issues.embedding` with a constant vector (`ARRAY_FILL(0.01…)`). All constant vectors point in the same direction, so cosine similarity to any query is **identical for all 50 patterns** – classification is meaningless. Replace with a bootstrap component (`ApplicationRunner`) that embeds `pattern_name + description` for rows with `embedding IS NULL` (or rows flagged as placeholder) using the real `EmbeddingService`. Seed rows with `NULL` embedding in SQL.
- [ ] **A1.6 (P1)** Flyway ownership: migrations live only in `reconciliation-engine`; AI worker runs `ddl-auto: validate` with no Flyway. Decide on a single owner (recommended: move migrations into `common` or a dedicated `db-migrations` module, run Flyway from one service) and document start-up order.
- [ ] **A1.7 (P1)** Add missing indexes/constraints: `UNIQUE(tenant_id, transaction_id)`, index on `reconciliation_failures(tenant_id, order_id)`, FK/consistency between `known_issues` and `resolution_suggestions`, add `auto_applied BOOLEAN` column (spec'd in roadmap but absent from V5).
- [ ] **A1.8 (P2)** Tenant partitioning (`LIST (tenant_id)`) + `TenantProvisioningService` (Phase 2.5 item) – or formally drop it and record an ADR.

### A2. Connect reconciliation → AI worker
- [ ] **A2.1 (P0) Topic mismatch.** Engine's `FailurePublisher` publishes `FailurePayload(event, failureReason)` to `recon.failures.{tenantId}`; AI worker listens on `reconguard.transactions.failures` expecting a flat `TransactionFailureEvent`. Choose one contract (recommend: publish `common.event.TransactionFailureEvent` to one topic `reconguard.transactions.failures`, keyed by `tenantId:orderId`, tenant in a header) and change the publisher accordingly. Add a contract test.
- [ ] **A2.2 (P0) AI worker has no Kafka (de)serialization config.** `application.yml` sets no consumer group/deserializer for `TransactionFailureEvent`, so the default String deserializer will fail. Add `ErrorHandlingDeserializer` + `JsonDeserializer` (trusted packages `io.reconguard.common.*`), `AckMode.RECORD`, `DefaultErrorHandler` + DLT.
- [ ] **A2.3 (P0) Two listeners on the same topic.** `TransactionFailureConsumer` (persists suggestions) and `DiagnosticEventListener` (only logs/searches) both consume `reconguard.transactions.failures` under different group IDs → duplicate work. Delete `DiagnosticEventListener` + `DiagnosticWorkerService` + `VectorSearchRepository` duplication, keep one pipeline.
- [ ] **A2.4 (P0) Broken native query.** `KnownIssueRepository.findSimilarIssues` selects `id, tenant_id, pattern, resolution_template` – none exist in `known_issues` – and orders by L2 (`<->`) instead of cosine. Used by `DiagnosticController` → runtime error. Rewrite against real columns/cosine, or remove it. `KnownIssue` entity should also map `embedding` if needed.
- [ ] **A2.5 (P0) Reconciliation engine depends on the whole AI worker.** `reconciliation-engine/build.gradle` has `implementation project(':ai-diagnostic-worker')` and `FailureDiagnosticEngineService` re-implements classification (with different thresholds: 0.80, no `AUTO_RESOLVED`). This duplicates the AI worker and couples a Spring Boot app jar to another app. Remove the dependency and `FailureDiagnosticEngineService`; extract anything truly shared (entities/DTOs) into `common` or a new `ai-core` library.
- [ ] **A2.6 (P1)** Make `metrics.recordEmbeddingLatency(..., "text-embedding-3-small")` use the real configured model name; `status` strings in `incrementSuggestionOutcome` comments (`HUMAN_REVIEW_REQUIRED`, `UNKNOWN_PATTERN`) don't match actual statuses (`AUTO_RESOLVED / AUTO_CLASSIFIED / NEEDS_REVIEW`).
- [ ] **A2.7 (P1)** Set `auto_applied = true` for `AUTO_RESOLVED` suggestions and decide what "auto-resolved" actually does (update `reconciliation_failures.resolved` and/or transaction status via event back to the engine). Currently nothing consumes a suggestion.
- [ ] **A2.8 (P1)** Persist rows into `reconciliation_failures` (table exists, **no entity/repository/writer uses it**) when the sweeper marks a transaction FAILED; store the `context_embedding`; link suggestions to failure IDs.
- [ ] **A2.9 (P1)** Idempotency in AI worker: re-delivery of the same failure event creates duplicate suggestions (random `sug-UUID`). Use deterministic ID `(tenantId, transactionId)` or a unique constraint + upsert.
- [ ] **A2.10 (P1)** Add `OpenAiEmbeddingService` dependency (`spring-ai-starter-model-openai`) – the class exists but the starter is not on the classpath, so the `openai` profile cannot start. Also verify `@ConditionalOnProperty` + `spring.ai.model.embedding` stay in sync via profiles (`application-ollama.yml`, `application-openai.yml`).

### A3. Fix the reconciliation engine's correctness bugs
- [ ] **A3.1 (P0) Consumer swallows exceptions → retry/DLQ never happens.** `TransactionConsumer` catches everything, evicts the idempotency key and returns, so the `DefaultErrorHandler` + `recon.dlq.*` path is never used (the only DLQ trigger is the hard-coded `FAIL-TEST` prefix). Rethrow after evicting the claim; mark non-retryable exceptions (validation errors) via `addNotRetryableExceptions`. **Remove the `FAIL-TEST` back-door.**
- [ ] **A3.2 (P0) Transaction / idempotency ordering.** Redis claim is taken before the DB transaction commits; a crash between them loses the event (claim set, nothing persisted). Options: claim → process → on failure evict (already done) **and** set TTL shorter than reprocess window, or move idempotency to a DB unique key (`transactions.transaction_id` PK already gives this) and treat Redis as optimisation only.
- [ ] **A3.3 (P0) DLQ topic naming.** Recoverer reads `X-Tenant-Id` header (the producer never sets it) and falls back to `recon.dlq.default`, but `DeadLetterConsumer` listens to `reconguard.transactions.raw.DLQ`. Align: producer sets the header, DLQ topics are pre-created (or use a pattern subscription `recon\.dlq\..*`), and the DLQ consumer persists to a `dead_letter_events` table.
- [ ] **A3.4 (P0) Concurrency race on the same order.** Two legs for one order can be processed concurrently (different partitions if keys differ; ingestion keys by `orderId` but the test controller keys by `transactionId`). Both read "no counterpart", both buffer → never matched until sweeper fails them. Fix: key all producers by `tenantId:orderId`, and/or use a Redis Lua script / `SELECT … FOR UPDATE SKIP LOCKED` / advisory lock around match-or-buffer.
- [ ] **A3.5 (P0)** Window buffer holds a **single** event per `tenant:order` key (second same-side event overwrites the first, e.g. partial payments/refunds). Define cardinality rules (1:1 vs 1:N), and handle duplicates of the same type.
- [ ] **A3.6 (P1) Two overlapping sweepers.** `UnresolvedTransactionSweeper` (24 h, hourly, count only) and `WindowUnresolvedTransactionSweeper` (5 min, marks FAILED & publishes). Windows (35 s Redis TTL, 120 s tolerance, 5 min sweep, 24 h sweep) are inconsistent with the "30-second window" in the spec. Define one policy, one sweeper, make all durations config properties, and remove the other.
- [ ] **A3.7 (P1)** `WindowUnresolvedTransactionSweeper` is `@Transactional` and publishes to Kafka inside the loop; if the DB commit fails after publishing, failure events are emitted for rows that remain UNRESOLVED (and vice versa). Use the transactional-outbox pattern (write to an `outbox` table in the same tx; a relay publishes) or at minimum publish after commit and make `markAsFailed` atomic with a status guard (`WHERE status='UNRESOLVED'`). Also `@Modifying` `markAsFailed` lacks `clearAutomatically`.
- [ ] **A3.8 (P1)** `WindowEvictionListener` only logs. It also requires Redis `notify-keyspace-events Ex` to be enabled (not set in `compose.yaml`) and a stale `RedisMessageListenerContainer` bean may conflict with Boot's. Either wire it to trigger failure handling or delete it.
- [ ] **A3.9 (P1)** `MatchingToleranceConfig` binds `reconguard.matching.*` but `application.yml` nests it as `reconguard.topics.matching.amount-tolerance / timestamp-tolerance` → properties are **ignored** and defaults used. Fix the property names/prefix.
- [ ] **A3.10 (P1)** Two matching implementations: `MatchingEngineService` (used) and `TransactionMatchingEngine` + `MatchResult` (flagged/matched/unmatched, **unused**). Pick one; ideally keep the pure `TransactionMatchingEngine` as the rule engine and call it from the orchestrator.
- [ ] **A3.11 (P0, raised by 0.10 – see 0F.4)** `TransactionRecord` field names don't match columns semantically (`gatewayTransactionId` → `matched_with_transaction_id`, `reconciledAt` → `updated_at`); `createdAt` is `nullable=false` but never set (no `@PrePersist`/`@CreationTimestamp`) → insert fails on the NOT NULL column unless DB default is used. Rename to `matchedWithTransactionId` / `updatedAt`, set timestamps, add `@Version` for optimistic locking.
- [ ] **A3.12 (P1)** Tenant scoping is manual: `findStaleUnresolved`, `markAsFailed(ids)`, `countBy…` ignore the tenant; `TenantContext` is set in the consumer but repositories never use it. Add a Hibernate `@TenantId`/filter or enforce `tenantId` in every query; add a test proving cross-tenant isolation.
- [ ] **A3.13 (P1)** Currency/FX: `hasSameCurrency` hard-fails on different currencies; spec wants FX normalisation with a cached rate (60 s refresh). Implement `FxRateService` (cache + provider stub) or document out-of-scope.
- [ ] **A3.14 (P1)** Remove `TestTransactionController` from main code (or put it behind a `dev` profile) – it is an unauthenticated write endpoint into the production topic.
- [ ] **A3.15 (P2)** Topic names are hard-coded in annotations in several classes; centralise in `@ConfigurationProperties` (`reconguard.topics.*`), and pre-create topics with `NewTopic` beans (partitions, replication, retention). Spec calls for per-tenant topics `tx.orders.{tenantId}` / `tx.payments.{tenantId}` – decide whether to keep the single-topic design and record an ADR.

### A4. Fix the ingestion service gaps
- [ ] **A4.1 (P0) Tenant is unauthenticated.** `TenantInterceptor` trusts `X-Tenant-Id` and falls back to `DEFAULT_GLOBAL`; HMAC uses a single hard-coded default secret (`super-secret-webhook-key-123`) for all tenants. Implement per-tenant secrets (table or config), resolve tenant from API key/signature, reject missing tenant (400/401) instead of defaulting, remove default secret (fail fast if unset).
- [ ] **A4.2 (P0)** The `TransactionEvent` body carries its own `tenantId`, but normalization overwrites it with the header value – ensure a mismatch is rejected, not silently replaced.
- [ ] **A4.3 (P1) Gateway payload normalization isn't connected.** `normalize(GatewayPayload)` exists; the controller only accepts a canonical `TransactionEvent`. Add `POST /api/v1/webhooks/stripe` (and a generic one) that accepts `StripeWebhookPayload`/`GatewayPayload`, and map amount units (minor → major, JPY zero-decimal) correctly.
- [ ] **A4.4 (P1) Idempotency semantic flaw.** Key is set to `PROCESSED` **before** the Kafka send; if send fails, retry is returned as a duplicate and the event is lost. Set key as `IN_PROGRESS`, update to `DONE` on broker ack, delete on failure; make the producer send synchronous-on-ack or return 503 on publish failure.
- [ ] **A4.5 (P1)** Response contract: spec says `{ingestion_id, received_at}`; code returns `status/transactionId/idempotencyKey/duplicate/processedAt`. Align or update the spec. Duplicate response should replay the *original* response body.
- [ ] **A4.6 (P1)** RFC 7807 for **all** errors: `HmacSecurityInterceptor` uses `response.sendError(...)` (HTML/default body) – not `application/problem+json`; move HMAC to a filter/interceptor that writes ProblemDetail; also handle missing `X-Idempotency-Key` header (400 problem+json), and replay protection (timestamp header + tolerance).
- [ ] **A4.7 (P1)** `RequestCachingFilter` / HMAC: root cause confirmed by audit = wrapper type mismatch, verification skipped entirely (**0F.2, P0**). Remaining here: verify HMAC over the raw cached bytes – add a test with a real signed request.
- [ ] **A4.8 (P1)** Kafka producer: key by `tenantId:orderId`, set `X-Tenant-Id`/correlation headers, add `enable.idempotence=true`, `delivery.timeout.ms`, and a fallback when the broker is down.
- [ ] **A4.9 (P2)** CDC/JDBC source for order events (Debezium or Kafka Connect JDBC) – Phase 1 leftover; either implement a minimal `orders` source or document that orders arrive via the same webhook and remove it from scope.
- [ ] **A4.10 (P2)** Add `GlobalExceptionHandler` coverage for validation (`MethodArgumentNotValidException`), malformed JSON, Kafka failures.

---

## 3. Phase B – Tests (P1)

- [ ] **B1 (P0) Fix the reconciliation-engine test compile errors** (these block every test in that module):
  - `MatchingEngineServiceTest` – constructor now requires `MeterRegistry` (use `SimpleMeterRegistry`).
  - `UnresolvedTransactionSweeperTest` (package `window`) – `UnresolvedTransactionSweeper` lives in `scheduler`; fix package/import and decide which sweeper this tests.
  - `ReconciliationEngineIntegrationTest` – uses `getMatchedWithTransactionId()` / `setTransactionId()`, which no longer exist on `TransactionRecord` (see A3.11). Also uses deprecated `@MockBean` → `@MockitoBean`.
  - Remove stray `reconciliation-engine/test.log`, `tmp/test.out` (commit hygiene, B-task 4.3).
- [ ] **B2** Unit tests: matching rules (amount tolerance boundary, currency, time window boundary, same-side rejection), `ResolutionSuggestionBuilder` thresholds at 0.90/0.75 boundaries, `FailureContextBuilder`, `IdempotencyGuard`, `HmacUtils`, `MaskingConverter` (card, IBAN, email patterns).
- [ ] **B3** Reconciliation integration tests (Testcontainers Postgres+pgvector, Redis, Kafka – **not H2**: the schema uses pgvector/partial indexes and Flyway; `h2` test dependency + `application-test.yml` should be replaced): match, duplicate, tenant isolation, timeout→FAILED→failure event, DLQ path, concurrent legs.
- [ ] **B4** Ingestion tests: signed request happy path, bad/missing signature (problem+json), duplicate idempotency key, Kafka failure path.
- [ ] **B5** AI worker integration test: failure event → embedding (stub `EmbeddingService` with deterministic vectors) → suggestion persisted with expected status. Review `DiagnosticFlowIntegrationTest` (exists, never validated) and make it run in CI.
- [ ] **B6** Cross-service E2E test (compose-based or Testcontainers network): webhook → Kafka → reconcile → failure → suggestion, asserting all three outcomes (MATCHED, AUTO_RESOLVED, NEEDS_REVIEW).
- [ ] **B7** Test coverage gate (JaCoCo ≥ 70% on service/logic packages).

---

## 4. Phase C – Observability (P1)

- [ ] **C1** Add `spring-boot-starter-actuator` + `micrometer-registry-prometheus` to **reconciliation-engine** and **ai-diagnostic-worker** (the engine registers meters but exposes nothing; AI worker lacks the Prometheus registry despite config). Ingestion too.
- [ ] **C2** Distributed tracing: `micrometer-tracing-bridge-brave` + zipkin reporter only in ingestion today. Add to the other two, enable Kafka observation (`spring.kafka.template.observation-enabled`, `listener.observation-enabled`), propagate trace context through Kafka headers, verify one trace spans ingestion → engine → AI worker in Zipkin.
- [ ] **C3** Add Prometheus (scrape the 3 services) and Grafana (provisioned datasource + dashboards) to `compose.yaml`; add Kafka exporter or `kafka-lag-exporter` for consumer lag.
- [ ] **C4** Dashboards (only the AI dashboard exists): reconciliation throughput, match rate, consumer lag, P50/P95/P99 `recon.match.latency` (target P99 < 1 s), idempotency hit/duplicate rate (add counters in ingestion + engine), DLQ count, AI classification rate (target ≥ 0.80), embedding/vector latency, Postgres latency (HikariCP + `pg_stat_statements`). Fix dashboard metric names to match actual meter names (`recon.ai.classification_rate` vs `reconguard.ai.*`).
- [ ] **C5** Logging: add `MdcFilter` use for Kafka listeners (put `tenantId`, `correlationId`, `traceId` in MDC from record headers), verify `MaskingConverter` is registered in all three `logback-spring.xml`, JSON log encoder for non-local profile.
- [ ] **C6** Alert rules (Prometheus): consumer lag > N, DLQ > 0, AI classification rate < 0.8, error rate > 1 %. Optional.
- [ ] **C7** Health: add readiness/liveness groups; `AiWorkerHealthIndicator` hard-codes model name and calls the model on every probe (cost + latency) – cache the result.

---

## 5. Phase D – Security & Management API (P1/P2)

- [ ] **D1** Add `spring-boot-starter-security` (+ OAuth2 resource server/JWT or API-key filter) to the engine/management API; tenant derived from the authenticated principal, never from a raw header. `SecurityUserContext` is currently a stub.
- [ ] **D2** Create the **Management API** (new `management-api` module or inside the engine) under `/api/v1/`:
  - `GET /reconciliations` (paginated, filter by status/date/tenant) · `GET /reconciliations/{id}` · `POST /reconciliations/{id}/resolve` · `GET /metrics/summary` · `POST/GET/DELETE /known-issues` (creating a known issue must compute and store its embedding).
- [ ] **D3** RFC 7807 problem responses on all management endpoints; OpenAPI/Swagger (springdoc) docs.
- [ ] **D4** RBAC: `RECON_VIEWER` (read), `RECON_ADMIN` (known-issue writes, resolve); method security + tests.
- [ ] **D5** Redis sliding-window rate limiting per `{tenantId}:{endpoint}` with tier limits (FREE 100/min, PRO 1000/min, ENTERPRISE configurable); return `429` + `Retry-After` problem+json.
- [ ] **D6** `Deprecation`/`Sunset` header support (`DeprecationService`) – only if a versioned endpoint is actually retired; otherwise drop.
- [ ] **D7** Secrets: remove plaintext DB password/webhook secret from `application.yml` and `compose.yaml` (use env vars / `.env` / Docker secrets); add `.env.example`; ensure `.mcp.json`, `*.log`, `tmp/` are in `.gitignore`.
- [ ] **D8** TLS for Postgres/Redis/Kafka in non-local profiles (`sslmode=verify-full`, `spring.data.redis.ssl.enabled`, Kafka SASL_SSL) via a `prod` profile.
- [ ] **D9** PII: confirm masking also applies to `FailureContextBuilder` text before it is sent to an external embedding API (OpenAI) – IDs, amounts, emails must be redacted/minimised.
- [ ] **D10** Dependency/CVE scan (OWASP Dependency-Check / `gradle dependencyCheckAnalyze`) wired into the build.

---

## 6. Phase E – Deployment & CI/CD (P2)

- [ ] **E1** Multi-stage `Dockerfile` per service (`eclipse-temurin:21-jdk` → `21-jre`, non-root user, layered jars, healthcheck).
- [ ] **E2** Extend `compose.yaml` (or add `compose.full.yaml`) with the 3 services, Prometheus, Grafana, Kafka UI, init job that pulls the Ollama model (`ollama pull nomic-embed-text`) and creates topics; use health-based `depends_on`; pin image tags (currently `ollama:latest`, `zipkin:latest`); set Kafka `KAFKA_AUTO_CREATE_TOPICS_ENABLE` deliberately; fix Kafka advertised listener for in-network use (`kafka:29092`) – current inter-broker listener is named `TRACK`, which is confusing.
- [ ] **E3** `Jenkinsfile` (or GitHub Actions): Build → Unit tests → OWASP check → Docker build → Trivy scan → Integration tests (Testcontainers) → Deploy staging → k6 → manual-approval prod.
- [ ] **E4** Helm chart: Deployment/Service/ConfigMap/Secret per service, probes, resource limits; `HorizontalPodAutoscaler` for the engine on Kafka consumer lag (min 2, max 20); decide whether per-tenant namespaces are in scope.
- [ ] **E5** k6 load test: 1,000 events/s for 5 min, assert P99 < 1 s and **zero duplicate MATCHED** records; document results.
- [ ] **E6** Gradle housekeeping: single dependency-version strategy (the `common` module pins `3.5.13`/Lombok `1.18.30` manually, others use the BOM); remove `Main.java` from `common`; set `bootJar`/`jar` flags consistently (`common` must be a plain library); remove `HELP.md`; add `.gitattributes` line endings; check in the Gradle wrapper (`gradle/`, `gradlew*` are currently untracked).
- [ ] **E7** `docker-compose.override.yml` for local-only ports/volumes (spec item).

---

## 7. Phase F – Documentation & Portfolio Polish (P2)

- [ ] **F1** `README.md` (root): what it is, architecture diagram (from `reconguard_architecture.pdf`/`architecture/`), quick start (`docker compose up`, run 3 apps, curl examples with signature generation), demo script that forces an unmatched transaction and shows the AI suggestion.
- [ ] **F2** Runbook: start infra, run tests, trigger failure, inspect Zipkin/Grafana, replay DLQ, rebuild embeddings.
- [ ] **F3** ADRs: virtual threads + blocking MVC, HNSW vs IVFFlat, single-topic vs per-tenant topics, outbox vs direct publish, embedding dimension choice, tenant-isolation strategy.
- [ ] **F4** Reconcile the doc sprawl: `PROJECT_ROADMAP.md`, `phase-2*.md`, `phase-3*.md`, `KAFKA_/REDIS_/MISCELLANEOUS_IMPLEMENTATION.md`, three `INTERVIEW_GUIDE*.md`. Update the checkboxes to reflect reality (use this file as the source), move interview/learning notes under `docs/`.
- [ ] **F5** Repo hygiene: commit the large pending change set in logical commits (91 changed files uncommitted on `master`; last commit is far behind), create a feature-branch workflow, delete `tmp/`, `test.log`, `HELP.md`, IDE files (`.idea/` workspace) from tracking.

---

## 7b. Phase G – Architecture Improvements & Additions

Source: review of the architecture guide (`architecture` / PDF). Part 1 fixes contradictions *inside the guide* (should be decided in Phase 0 / ADRs and then reflected in Phase A); Parts 2–3 are additions. Tags: **[FIX]** design flaw · **[ADD]** new capability · **[STRETCH]** optional showpiece.

### G1. Design flaws in the guide (decide first, implement with Phase A)
- [ ] **G1.1 [FIX] (P0) Partition/message key.** Guide partitions by `transactionId`, but order and payment legs have different IDs → legs land on different consumers and race. Key every event by `tenantId:orderId` (or a derived `correlation_key` when the gateway lacks `orderId`). Defines the payload field `correlationKey` in the canonical event. Related: A3.4, A4.8.
- [ ] **G1.2 [FIX] (P0) Window/lifecycle model.** Guide has a 30 s tumbling window and ±120 s tolerance (contradictory); real payments (SEPA/ACH/BNPL) settle hours–days later (our own seeds ISSUE-002/005/026 say so). Replace with a lifecycle `PENDING → MATCHED | LATE_MATCHED | EXPIRED → FAILED`, and a **per-gateway / per-payment-method SLA** (config in `tenants.config`/`gateway_profiles`). A late payment must reopen and auto-close an already-FAILED record (and mark its suggestion obsolete). Related: A3.6.
- [ ] **G1.3 [FIX] (P0) Durable pending state.** Redis is currently the only home for in-flight window state (TTL loss/restart = lost legs). Choose and implement one: **(a)** Postgres `pending_legs` table with `UNIQUE (tenant_id, correlation_key, leg_type)` + `SELECT … FOR UPDATE SKIP LOCKED`, Redis as read cache only; or **(b)** Kafka Streams windowed join (see G3.1). Record as ADR. Related: A3.4/A3.5.
- [ ] **G1.4 [FIX] (P1) Idempotency source of truth.** Move the authoritative duplicate check to the DB (`inbox_messages(message_id PK)` or unique constraint), keep Redis `SETNX` as a fast-path optimisation. Replace "exactly-once" claims in docs with "at-least-once delivery + idempotent processing". Add `messageId` to the canonical event (guide's `TransactionEvent` has it; ours doesn't). Related: A3.2.
- [ ] **G1.5 [FIX] (P1) Topic topology.** Replace per-tenant topics (1,000 tenants × 3 topics × 12 partitions ≈ 36k partitions) with shared topics (`recon.transactions`, `recon.failures`, `recon.dlq`) keyed by tenant+order and `X-Tenant-Id` header; optional dedicated topics only for an enterprise tier. Fix partition counts (single consistent number; max HPA replicas ≤ partitions). Related: A3.15.
- [ ] **G1.6 [FIX] (P1) Match cardinality.** Support 1:N and N:1 (partial payments, split captures, refunds) with explicit rules and a `match_groups` table (`group_id`, legs, residual amount). Extend `TransactionEventType` with `REFUND`, `CHARGEBACK`, `FEE`, `SETTLEMENT`. Related: A3.5.
- [ ] **G1.7 [FIX] (P1) Tenant isolation as defence in depth.** Add Postgres **row-level security** (`ALTER TABLE … ENABLE ROW LEVEL SECURITY`, policy `tenant_id = current_setting('app.tenant_id')`, set per connection/transaction via a Hibernate/Hikari hook) on top of the repository filter; add a test that a query without tenant context returns nothing. Related: A3.12.
- [ ] **G1.8 [FIX] (P2) Multi-tenancy deployment model.** Resolve the contradiction between "namespace per tenant", shared HPA and shared consumer groups: default = shared worker pool with per-tenant quotas; isolated namespace/deployment only as an enterprise option (ADR). Related: A1.8, E4.
- [ ] **G1.9 [FIX] (P2) Guide inconsistencies.** Single set of AI thresholds (0.90/0.75, with `known_issues.confidence_threshold` as optional per-issue override), single partition count, KEDA (Kafka scaler) instead of a custom `kafka_consumer_group_lag` external metric, add `tenant_id` (nullable = global) to `known_issues`, update the guide document accordingly.

### G2. Additions – reconciliation core
- [ ] **G2.1 [ADD] (P1) Transactional outbox.** `outbox_events` table written in the same DB transaction as the status change; a relay (poller or Debezium outbox router) publishes to Kafka. Replaces direct `FailurePublisher.send` in the sweeper. Related: A3.7.
- [ ] **G2.2 [ADD] (P1) `Money` value type.** ISO 4217 currency table with minor-unit exponents (JPY=0, KWD=3), safe scale/rounding rules, conversion helpers; replace raw `BigDecimal` + `String currency`. FX rate resolved **as of event time** (historical rates table + 60 s cached live rate), not "now". Related: A3.13.
- [ ] **G2.3 [ADD] (P1) Per-tenant rules/config.** `tenants.config` JSONB + typed `TenantConfig` (amount tolerance, time SLA per gateway, auto-resolve opt-in, enabled gateways) with cache and change events; matching reads it instead of global config.
- [ ] **G2.4 [ADD] (P2) Three-way match.** Third source: bank/processor settlement files (CSV/CAMT.053 import endpoint → `SETTLEMENT` events). Match order ↔ payment ↔ settlement; report each leg's state.
- [ ] **G2.5 [ADD] (P2) Reprocessing/backfill.** Documented, tested procedure + admin endpoint to replay a tenant/time range (offset reset or re-publish from DB) with idempotency guarantees.
- [ ] **G2.6 [ADD] (P2) Data lifecycle.** Time-based sub-partitioning (monthly) of `transactions`/`reconciliation_failures`, retention policy + archival job, GDPR erasure endpoint for PII-bearing fields.

### G3. Additions – stream processing & resilience
- [ ] **G3.1 [STRETCH] Kafka Streams windowed join** (orders ⋈ payments by correlation key, grace period, RocksDB store + changelog topic, `exactly_once_v2`) as the alternative implementation of G1.3(b); keep behind a profile and benchmark against the Postgres approach. Document the trade-off versus the virtual-threads blocking model.
- [ ] **G3.2 [ADD] (P1) Non-blocking retries.** Replace blocking `DefaultErrorHandler` back-off (halts the partition) with `@RetryableTopic` retry topics (`-retry-1s`, `-retry-4s`, …) + final DLT. Related: A3.1/A3.3.
- [ ] **G3.3 [ADD] (P1) Embedding circuit breaker.** Resilience4j circuit breaker + bulkhead + timeout around embedding calls; on open circuit persist the failure as `NEEDS_REVIEW` (`reason=EMBEDDING_UNAVAILABLE`) and enqueue for re-classification instead of blocking the consumer.
- [ ] **G3.4 [ADD] (P1) DLQ replay tool.** Admin endpoint/CLI to list, inspect, re-drive or discard dead-lettered events per tenant; persisted `dead_letter_events` table (A3.3) with reason + stack summary; Prometheus metric for DLQ depth and alert > 10.
- [ ] **G3.5 [ADD] (P2) Schema Registry + Avro/Protobuf** (Confluent Schema Registry in compose) for `TransactionEvent` and `TransactionFailureEvent`, with backward-compatible evolution rules; removes dependence on Java class names in `__TypeId__` headers. Add a compatibility check to CI.
- [ ] **G3.6 [ADD] (P2) Back-pressure & per-tenant fairness.** Kafka client quotas / per-tenant concurrency caps so one noisy tenant can't starve others; metric `recon.tenant.inflight`.

### G4. Additions – AI layer
- [ ] **G4.1 [ADD] (P0/P1) Rules-first classification pipeline.** `FailureClassifier` chain: (1) deterministic rules (amount delta within FX band → `FX_DRIFT`; missing leg + async gateway/method → `SETTLEMENT_LAG`; same txn id twice → `DUPLICATE`; zero-decimal currency mismatch; refund-before-settlement …) → (2) hybrid vector search (SQL filter on gateway/failure code/amount band + cosine rank) → (3) optional RAG explanation. Each result records `classifier=RULE|VECTOR|LLM` for evaluation.
- [ ] **G4.2 [ADD] (P1) Better embedding input.** Include gateway, failure code, delay in seconds, amount delta/ratio (rounded buckets), payment method – and exclude raw IDs/amounts that add noise or PII. Version the context template (`context_version`) and re-embed when it changes.
- [ ] **G4.3 [ADD] (P1) Feedback loop.** `suggestion_feedback` (accepted / rejected / corrected-to-issue, actor, timestamp); Management API endpoint; metrics for **precision of AUTO_RESOLVED** and acceptance rate by tier; threshold calibration job that proposes new cut-offs from feedback.
- [ ] **G4.4 [ADD] (P1) Guardrails on auto-resolve.** Allow-list of safe, reversible actions (`RETRY`, `EXTEND_WINDOW`, `LINK_DUPLICATE`), tenant opt-in flag, dry-run mode (default on), full audit trail, undo, and mandatory human approval for any money-moving action; never auto-apply below the per-issue threshold. Related: A2.7.
- [ ] **G4.5 [ADD] (P1) Anomaly Guard (the "A" in the project title).** `AnomalyDetectionService` per tenant×gateway: EWMA/z-score on amount and match-latency, mismatch-rate spike, velocity/burst, near-duplicate payments (same amount+payer within N s). Emits `AnomalyEvent` → `anomalies` table + metric + notification. Currently nothing in the code implements anomaly detection.
- [ ] **G4.6 [ADD] (P2) Recurring-failure discovery.** Periodic job clusters recent `reconciliation_failures.context_embedding` (HNSW neighbours / simple DBSCAN) that matched no known issue (`NEEDS_REVIEW`), proposes new `known_issues` for admin approval.
- [ ] **G4.7 [ADD] (P2) Offline evaluation harness.** Labelled failure dataset (generated by G6.1) + test that reports top-1/top-3 accuracy and precision per tier; fails CI if accuracy regresses below a threshold. Required to justify the "≥ 80 % classified" claim.
- [ ] **G4.8 [STRETCH] RAG explanation.** Chat model (Ollama/OpenAI) receives top-K issues + failure context and returns a constrained JSON `{explanation, action, confidence}`; validated against the allow-list; clearly separated from the auto-apply path.

### G5. Additions – security, observability, product surface
- [ ] **G5.1 [ADD] (P1) Webhook secret management.** `tenant_webhook_secrets` (ref, `valid_from`, `valid_to`, status) allowing two active secrets for rotation; signature over `timestamp.body` with ±5 min replay window; rotation endpoint + tests. Related: A4.1.
- [ ] **G5.2 [ADD] (P1) Tenant-claim consistency.** Management API verifies `{tenantId}` path variable equals the JWT `tenantId` claim (403 + problem+json otherwise). Related: D1.
- [ ] **G5.3 [ADD] (P1) Append-only audit log.** `audit_events` (actor, tenant, action, entity, before/after, correlationId); written for resolve, known-issue changes, auto-applied suggestions, config changes, DLQ replays. DB role has INSERT-only privilege.
- [ ] **G5.4 [ADD] (P2) Identity provider.** Keycloak in compose (realm export committed) replacing the custom Spring Authorization Server idea; JWT converter maps `tenantId` + roles.
- [ ] **G5.5 [ADD] (P1) Business metrics & SLOs.** `recon.amount.at_risk` (sum of unmatched value by currency/tenant), `recon.match.rate`, `recon.time_to_match` histogram, `recon.late_match.count`, outbox lag, per-tenant consumer lag; define SLOs (e.g. 99 % of events reconciled < 1 s, ≥ 80 % AI classification) with burn-rate alerts. Add to dashboards (C4).
- [ ] **G5.6 [ADD] (P2) Trace/metric exemplars** linking Grafana latency panels to Zipkin traces; propagate `correlationId` + W3C trace headers through Kafka and the outbox.
- [ ] **G5.7 [ADD] (P2) Notifications.** Pluggable notifier (Slack webhook, email, outbound signed webhook) fired for `NEEDS_REVIEW`, anomalies and DLQ growth, with per-tenant routing config.
- [ ] **G5.8 [ADD] (P2) Reporting.** CSV/JSON export of reconciliations (streamed) and a scheduled daily summary per tenant.
- [ ] **G5.9 [ADD] (P2) Tenant onboarding API.** `POST /admin/tenants` creates tenant row, webhook secret, partition (A1.8), default config, and Kafka ACL/quota; idempotent and audited.
- [ ] **G5.10 [STRETCH] Operator dashboard.** Small React/Vite SPA (failures table, suggestion detail with confidence, accept/reject/resolve, metrics tiles) served behind the Management API – gives the portfolio a visible front end.

### G6. Additions – testing & demo tooling
- [ ] **G6.1 [ADD] (P1) Event simulator.** Standalone module/CLI generating realistic traffic per tenant: matched pairs, late payments, duplicates, reordering, amount drift, missing legs, FX variance, malformed payloads; configurable rate/seed. Used for load tests (E5), AI evaluation (G4.7), demos, and the E2E test (B6).
- [ ] **G6.2 [ADD] (P1) Property-based tests (jqwik).** Invariants: a leg is matched at most once; matching is symmetric/order-independent; replaying any event sequence yields the same final state; no cross-tenant matches.
- [ ] **G6.3 [ADD] (P2) Chaos/resilience tests (Toxiproxy).** Kill/slow Redis, Postgres, Kafka, Ollama mid-run; assert no lost or duplicated MATCHED records and that the pipeline recovers.
- [ ] **G6.4 [ADD] (P2) Contract tests** for each producer/consumer pair (ingestion→engine, engine→AI worker, DLQ), generated from the Phase 0 contract table (or Pact / Schema Registry compatibility).

### G7. Architecture decision records (write alongside the tasks above)
- [ ] ADR-010 Partition key = `tenantId:orderId`/correlation key (G1.1)
- [ ] ADR-011 Lifecycle model + per-gateway SLAs instead of fixed 30 s window (G1.2)
- [ ] ADR-012 Pending-state store: Postgres vs Kafka Streams (G1.3/G3.1)
- [ ] ADR-013 DB-authoritative idempotency, at-least-once semantics (G1.4)
- [ ] ADR-014 Shared topics vs per-tenant topics (G1.5)
- [ ] ADR-015 Transactional outbox (G2.1)
- [ ] ADR-016 Rules-first, vector-fallback classification; auto-resolve guardrails (G4.1/G4.4)
- [ ] ADR-017 Tenant isolation: repository filter + Postgres RLS (G1.7)

---

## 8. Suggested Execution Order

0. **Phase 0 (Discrepancy Audit) – audit, report, baseline tag DONE.** Next: answer the open decisions in §10 (feed ADRs in G7), then **0F.1–0F.9 + 0F.28** (clean-DB boot, contract, created_at, HMAC, green build) – these map onto A1/A2/A3.11/B1 below, so do them as those tasks; then doc fixes 0F.24/0F.25.
1. **G1 decisions + A1 → A2 → A3.1–A3.5/A3.9 → B1** — clean DB, correct partition key/lifecycle/pending-state model, a connected pipeline and a compiling test suite (the "walking skeleton"). Implement G1.1–G1.4 while doing A3, not afterwards (otherwise A3 gets rewritten).
2. **B2–B6 + G6.1/G6.2** alongside the rest of A3/A4 — lock behaviour with tests and the simulator before refactoring (sweepers, outbox G2.1, tenant scoping G1.7).
3. **G2.1, G3.2–G3.4, G4.1–G4.4** — outbox, non-blocking retries, circuit breaker, DLQ replay, rules-first AI with guardrails.
4. **C1–C5 + G5.5** — metrics/tracing/business metrics so you can *see* the pipeline.
5. **D1–D5 + G5.1–G5.3** — auth, management API, rate limiting, webhook secret rotation, audit log.
6. **G4.5–G4.7, G5.7–G5.9, G2.2–G2.4** — anomaly guard, feedback calibration/evaluation, notifications, onboarding, money/FX/three-way match.
7. **E1–E5** — containers, CI/CD, load test (driven by G6.1).
8. **F + G7 + stretch items (G3.1, G4.8, G5.10)** — docs/ADRs/polish (continuous; finalize last).

## 9. Definition of Done (project level)

- `docker compose up` + three `bootRun`s (or the full compose) starts from an **empty** database with no manual SQL.
- A signed webhook for an order + matching payment ends as `MATCHED` in Postgres exactly once, even when replayed or delivered twice.
- A lone leg is marked `FAILED` after the configured window, a failure event is emitted, the AI worker stores a `resolution_suggestions` row with the correct tier, and it is visible via the Management API.
- A poison message lands in the per-tenant DLQ and is persisted/alerted.
- One Zipkin trace and one Grafana dashboard cover ingest → reconcile → AI.
- `./gradlew build` is green (unit + Testcontainers integration tests) in CI; k6 shows P99 < 1 s at 1,000 ev/s.

## 10. Open Decisions (answer before starting the related tasks)

1. **Embedding model / dimension** – Ollama `nomic-embed-text` (768) as the default, or OpenAI (1536)? (A1.4, A2.10)
2. **Topic topology** – keep the single `reconguard.transactions.raw` topic, or per-tenant topics per the guide? (A3.15)
3. **Flyway owner** – which module owns migrations? (A1.6)
4. **Failure handoff** – transactional outbox or direct publish? (A3.7)
5. **Scope of multi-tenancy** – partitions + namespaces per tenant, or row-level `tenant_id` only? (A1.8, E4)
6. **Management API home** – new `management-api` module or part of the engine? (D2)
7. **Pending-state store** – Postgres `pending_legs` (recommended for portfolio scope: simpler, durable, testable) or Kafka Streams windowed join? (G1.3/G3.1)
8. **AI pipeline** – rules-first with vector fallback (recommended) or vector-only as in the guide? (G4.1)
9. **Auto-resolve** – ship it in dry-run/opt-in mode only (recommended), or fully automatic? (G4.4)
10. **Which stretch items** are worth the time: Kafka Streams join (G3.1), RAG explanation (G4.8), operator dashboard (G5.10), three-way match (G2.4)?
