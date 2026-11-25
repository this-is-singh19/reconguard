# ReconGuard – Phase 0 Discrepancy Report

_Audit date: 2026-11-25. Sources: code read-through, clean-DB migration run (pgvector pg16), `./gradlew clean build --continue`, per-module dependency dumps, git state, docs-vs-code check, runtime smoke test (§9)._

Decision legend: **code** = keep code, fix guide/docs · **guide** = adopt guide, change code · **new** = neither; new design (see task) · **fix** = plain bug. Fix tasks are `0F.*` in `PROJECT_COMPLETION_TASKS.md` (they point at existing Phase A–G tasks where one already exists).

---

## 1. Blockers found (system cannot run end-to-end today)

| # | Finding | Evidence | Fix task |
|---|---|---|---|
| X1 | **V5 migration fails on a clean DB** → Flyway aborts → reconciliation-engine cannot start; `resolution_suggestions` never exists → AI worker fails `ddl-auto: validate` | `ERROR: there is no unique constraint matching given keys for referenced table "transactions"` | 0F.1 (A1.1) |
| X2 | **HMAC signature is never verified.** `RequestCachingFilter` wraps with its private `CachedBodyRequestWrapper`; `HmacSecurityInterceptor` only verifies when `request instanceof ContentCachingRequestWrapper` → branch skipped, any non-blank `X-Signature` accepted | `HmacSecurityInterceptor.java:27`, `RequestCachingFilter.java:21` | 0F.2 (new, P0) |
| X3 | **Engine → AI worker contract broken**: `FailurePayload(event, reason)` → `recon.failures.{tenantId}` vs AI listening `reconguard.transactions.failures` for flat `TransactionFailureEvent` | `FailurePublisher.java:22`, `TransactionFailureConsumer.java:54` | 0F.3 (A2.1) |
| X4 | **`transactions.created_at` never set** – `TransactionRecord.createdAt` is `nullable=false`, no `@PrePersist`/`@CreationTimestamp`; Hibernate sends explicit NULL so the DB default doesn't apply → every insert (matched & unresolved) fails | `TransactionRecord.java:46` | 0F.4 (A3.11 → P0) |
| X5 | **Embedding dimension 1536 vs model 768** (`nomic-embed-text`) → every Ollama vector rejected (`different vector dimensions 1536 and 768`) | migration run | 0F.5 (A1.4) |
| X6 | **Seed embeddings are constant vectors** (`ARRAY_FILL`) → cosine similarity = 1.0 for every pair; top-K is arbitrary (verified ISSUE-001 vs 002/006/050 distance 0.000000) | migration run | 0F.6 (A1.5) |
| X7 | **reconciliation-engine test sources don't compile** (4 errors) → zero engine tests run | build log | 0F.7 (B1) |
| X8 | **AI worker integration test fails**: `Schema-validation: missing table [known_issues]` – test DB never gets migrations (they live in the engine module) | build log | 0F.8 (A1.6 + B5) |
| X9 | **AI worker has no Kafka JSON consumer config** → Boot default String deserializer; `containerFactory="kafkaListenerContainerFactory"` is Boot's default in the worker app, but the engine's custom `TransactionEvent`-typed factory when loaded inside the engine | `ai-diagnostic-worker/application.yml` (no `spring.kafka`) | 0F.3 (A2.2) |

---

## 2. Guide (`architecture` / PDF) vs code — task 0.1

| Item | Guide says | Code says | Decision |
|---|---|---|---|
| Spring Boot | 3.3+ | 3.5.13 | code (update guide) |
| AI library | LangChain4j | Spring AI 1.0.0 (Ollama starter) | code |
| Webhook endpoint | `POST /api/v1/webhooks/{tenantId}/payment` | `POST /api/v1/webhooks/transactions`, tenant from `X-Tenant-Id` | new – tenant from authenticated key/secret (A4.1); keep single path + add gateway paths (A4.3) |
| Signature header | `X-Webhook-Signature: sha256=<hmac>` | `X-Signature: <hex>` (and not verified – X2) | guide format (`sha256=` prefix + timestamp, G5.1) |
| Ingestion response | `{ingestion_id, received_at}` | `{status, transactionId, idempotencyKey, duplicate, processedAt}` | decide in A4.5 |
| Canonical event | has `messageId` | no `messageId` | guide (G1.4) |
| Topics | `tx.orders.{t}`, `tx.payments.{t}`, `recon.failures.{t}` | `reconguard.transactions.raw` + `recon.failures.{t}` (publisher) + `reconguard.transactions.failures` (consumer) | new – shared topics keyed by tenant (G1.5) |
| Partition key | `transactionId` | ingestion `orderId`; test controller `transactionId` | new – `tenantId:orderId` (G1.1) |
| Partitions | 12 (orders/payments), 6 (failures), compose `KAFKA_NUM_PARTITIONS: 12` | auto-created, broker default 1 | new – NewTopic beans (A3.15) |
| Producer | `acks=all`, `enable.idempotence=true` | `acks=all`, `retries=3`, idempotence not set | guide (A4.8) |
| Window | 30 s tumbling, Redis HASH `{tenantId}:{transactionId}` | 35 s TTL string key `tenant:orderId`, single event | new – lifecycle model (G1.2/G1.3) |
| Amount tolerance | ±0.02 (one section) and ±0.01 (another) | 0.02 default (yml ignored, see §4) | code 0.02, per-tenant later (G2.3) |
| Timestamp tolerance | ±120 s (contradicts 30 s window) | 120 s default | new (G1.2) |
| AI thresholds | 0.90/0.75 table **and** "> 0.85 → AUTO_CLASSIFIED" | 0.90/0.75 (worker); 0.80 (engine's unused `FailureDiagnosticEngineService`) | code 0.90/0.75 (G1.9) |
| IDs | UUID PK + `tenant_id UUID REFERENCES tenants` | `VARCHAR(64)` natural keys, no FK on `transactions.tenant_id` | code (VARCHAR) – record ADR |
| `known_issues` columns | `id, title, resolution_template, confidence_threshold` | `issue_id, pattern_name, category, description, recommended_action` (no threshold, no tenant_id) | code + add `tenant_id`/`confidence_threshold` (G1.9) |
| `resolution_suggestions` | `failure_id → reconciliation_failures(id)` | `transaction_id` + composite FK to `transactions` (broken, X1) | new – link to failure id (A2.8) |
| Partitioning | `PARTITION BY LIST (tenant_id)` | none | open decision §10.5 (A1.8) |
| Class names | `ReconciliationKafkaConsumer`, `WindowBuffer` | `TransactionConsumer`, `WindowBufferService` | code |
| Consumer executor | `newVirtualThreadPerTaskExecutor()` | none; virtual-threads flag not effective (§4) | guide (0F.10) |
| Management API / RBAC / rate limiting / Helm / Jenkins / k6 | specified | absent | planned (D, E) |

---

## 3. Schema — task 0.4 (clean pgvector DB, migrations applied in order)

| Migration | Result |
|---|---|
| V1 | OK – creates `transactions` (PK `transaction_id` only) |
| V2 | OK – `NOTICE: relation "transactions" already exists, skipping` → V2's FK to `tenants` and its version of the table are **silently lost**; creates `tenants`, `reconciliation_failures`, seeds `tenant-space-99` |
| V3 | **missing** (version gap) |
| V4 | OK – `known_issues` `vector(1536)` + HNSW |
| V5 | **FAILED** (X1) |
| V6 / V8 | OK – 4 + 46 seeds, constant embeddings (X6) |
| V7 | OK – adds `reconciliation_failures.context_embedding vector(1536)` |
| `tmp/V7__add_id_to_transactions.sql` | not applied; **version collision** with real V7, would fail (PK already exists); file is **staged in git** |

Entity / query mismatches:
- Two JPA entities on table `transactions`: `domain.TransactionRecord` (`@Id transaction_id`, used) and `model.Transaction` + `TransactionId` (`@EmbeddedId (transaction_id, tenant_id)` ≠ DB PK, unused).
- `TransactionRecord.amount` has no precision/scale (DB `numeric(18,4)`); field names `gatewayTransactionId`/`reconciledAt` map to `matched_with_transaction_id`/`updated_at`.
- Duplicate indexes: `idx_transactions_order_tenant` ≡ `idx_tx_tenant_order_status`.
- `KnownIssueRepository.findSimilarIssues`: `column "id" does not exist` (also `tenant_id`, `pattern`, `resolution_template`; uses L2 `<->`). Used via `VectorSearchRepository`/`DiagnosticService`.
- `findTopKSimilarIssues` works only with 1536-d input.
- `reconciliation_failures` exists, nothing reads/writes it.

---

## 4. Config — task 0.5

| Item | Code reads | Config provides | Effect |
|---|---|---|---|
| Matching tolerance | `reconguard.matching.max-amount-difference`, `.max-time-window` | `reconguard.topics.matching.amount-tolerance`, `.timestamp-tolerance` | both prefix **and** names wrong → defaults always used |
| Virtual threads | `spring.threads.virtual.enabled` | only in `common/src/main/resources/application.yml` | each app's own `application.yml` shadows the library's same-named file → **not enabled** in any app |
| Failure topic | `FailurePublisher` hard-codes `recon.failures.` | `reconguard.topics.failed-transactions: reconguard.transactions.failures` (engine yml) | yml key only read by `DiagnosticEventListener` |
| Engine JSON trust | factory: `addTrustedPackages("io.reconguard.*")` | yml: `spring.json.trusted.packages: io.reconguard.common.model`, yml value-deserializer | yml consumer settings dead (custom `ConsumerFactory` overrides); factory is typed `TransactionEvent` and shared by **all** listeners in the engine (DLQ, AI listeners) |
| Consumer group IDs | engine `reconguard-reconciliation-group` (annotation + yml), fallback `reconciliation-service-group`; DLQ `reconguard-dlq-group`; AI `ai-diagnostic-worker-group` **and** `${spring.kafka.consumer.group-id:reconguard-ai-diagnostic-group}` (→ engine's group when loaded in engine) | — | 2 AI groups → each failure processed twice; inside engine a third copy |
| AI provider / model inside engine | AI beans loaded via scan | engine yml has `reconguard.ai.provider` but **no `spring.ai.*`** | engine's embedding model = Spring AI default, not `nomic-embed-text` |
| Webhook secret | `${reconguard.webhook.secret:super-secret-webhook-key-123}` | not in any yml | hard-coded default secret in source |
| DLQ | `recon.dlq.{X-Tenant-Id}` | header never produced; `DeadLetterConsumer` listens `reconguard.transactions.raw.DLQ` | DLQ consumer never receives; backoff is `maxElapsedTime 15s`, not "3 retries" as docs say |
| Sweeper interval | `${reconguard.sweeper.interval-ms:60000}` | not in yml | default only; 5-min expiry hard-coded |
| Actuator (AI worker) | — | `management.endpoint.prometheus.enabled`, `metrics.distribution.sla` (deprecated in Boot 3.5 → `access`, `slo`) and no `micrometer-registry-prometheus` | `/actuator/prometheus` 404 |
| Ports | 8080 engine / 8081 ingestion / 8082 AI | matches CLAUDE.md | OK |
| Kafka listeners (compose) | — | inter-broker listener named `TRACK`; typo `KAFKA_TRANSACTION_STATE_LOG_REREPLICATION_FACTOR` (should be `…_REPLICATION_FACTOR`) | transactions state log would default to RF 3 on a 1-broker cluster |
| Tenant default | `TenantInterceptor` falls back to `DEFAULT_GLOBAL` | — | unauthenticated tenant (A4.1) |

---

## 5. Cross-module contract table — task 0.6

| Hop | Producer (topic · key · headers · payload) | Consumer (topic · group · expected type) | Status |
|---|---|---|---|
| ingestion → engine | `reconguard.transactions.raw` · `orderId` · `__TypeId__` only · `common.model.TransactionEvent` | `reconguard.transactions.raw` · `reconguard-reconciliation-group` · `TransactionEvent` (ErrorHandlingDeserializer) | **OK** (no tenant header, no messageId) |
| test controller → engine | same topic · key **`transactionId`** | same | key mismatch (race, A3.4) – remove (A3.14) |
| engine → AI | `recon.failures.{tenantId}` · `transactionId` · `__TypeId__=…FailurePublisher$FailurePayload` · `{event:{…}, failureReason}` | `reconguard.transactions.failures` · `ai-diagnostic-worker-group` + 2nd group · flat `common.event.TransactionFailureEvent` | **BROKEN** (topic, payload shape, type header) |
| engine → DLQ | `recon.dlq.{X-Tenant-Id}` → always `recon.dlq.default` | `reconguard.transactions.raw.DLQ` · `reconguard-dlq-group` | **BROKEN** (topic) + unreachable (consumer swallows exceptions except `FAIL-TEST*`) |
| AI → engine (auto-resolve) | none | none | not designed (A2.7) |

---

## 6. Build / dependencies — task 0.7

Baseline `./gradlew clean build --continue`: **FAILED**.
- `:reconciliation-engine:compileTestJava` – 4 errors: `UnresolvedTransactionSweeperTest` (wrong package import, `setTransactionId`), `ReconciliationEngineIntegrationTest:103-104` (`getMatchedWithTransactionId`), `MatchingEngineServiceTest:54` (missing `MeterRegistry` arg).
- `:ai-diagnostic-worker:test` – `DiagnosticFlowIntegrationTest` context fails: missing table `known_issues`.
- ingestion: 5/5 tests pass. common: no tests.
- `@MockBean` (deprecated for removal) at 5 sites.

| Module | Notable present | Notable absent / odd |
|---|---|---|
| common | starter-web, starter-validation **pinned 3.5.13 manually**, Lombok **1.18.30** pinned | ships an `application.yml` (shadowed); `Main.java`; drags starter-web into every consumer |
| ingestion | actuator, brave + zipkin reporter, data-redis | prometheus registry; `assertj-core` pinned manually; no zipkin endpoint config |
| engine | data-jpa, flyway, redis, micrometer-core, **`project(':ai-diagnostic-worker')`**, H2 (test) | actuator, prometheus, tracing |
| AI worker | actuator, Spring AI 1.0.0 BOM + Ollama starter, flyway-database-postgresql (no migrations), Testcontainers | prometheus registry, OpenAI starter (but `OpenAiEmbeddingService` exists), tracing |

Other: Lombok split 1.18.30 vs 1.18.44; Spring AI 1.0.0 on Boot 3.5.13 (verify compatibility / bump to latest 1.0.x); misleading Boot comment in `common/build.gradle`; duplicate commented `spring-kafka-test` in engine build.

---

## 7. Dead / duplicate / leftover code — task 0.8

| Item | Decision |
|---|---|
| `TransactionMatchingEngine` + `MatchResult` (unused bean) | merge – becomes pure rule engine called by `MatchingEngineService` (A3.10) |
| `model.Transaction` + `TransactionId` (2nd entity on `transactions`) | delete |
| `FailureDiagnosticEngineService` (engine copy of AI classification, 0.80) | delete (A2.5) |
| `DiagnosticEventListener` + `DiagnosticWorkerService` + `VectorSearchRepository` (2nd AI pipeline) | delete (A2.3) |
| `DiagnosticController` + `health.service.DiagnosticService` + `KnownIssueRepository.findSimilarIssues` | rewrite on cosine/real columns or delete (A2.4) |
| `UnresolvedTransactionSweeper` (24 h, count only) vs `WindowUnresolvedTransactionSweeper` | merge into one (A3.6) |
| `WindowEvictionListener` + custom `RedisMessageListenerContainer` (keyspace events not enabled) | delete (A3.8) |
| `TestTransactionController` + commented-out code | delete or `dev` profile (A3.14) |
| `FAIL-TEST` hook in `TransactionConsumer` | delete (A3.1) |
| `SecurityUserContext` (stub, unused) | delete until D1 |
| `GatewayPayload` / `StripeWebhookPayload` (unit-tested, not reachable over HTTP) | keep, wire (A4.3) |
| `common/Main.java`, `common/application.yml` | delete (move virtual-threads flag to each app) |
| `HELP.md`, `tmp/` (incl. staged `tmp/V7…sql`, `tmp/test.out`, `tmp/pdfs/`), `reconciliation-engine/test.log` | delete |

---

## 8. Repo / VCS — task 0.9

- One commit (`1a92088`, 2026-07-20, ~4 months old). Pending: 63 modified, 50 staged, 27 untracked (incl. 3 migrations V1/V7/V8, `MatchingEngineServiceTest`, `TestTransactionController`, gateway DTOs, all docs, Gradle wrapper).
- `.gitignore` misses `*.log`, `tmp/`, `.mcp.json`; Gradle wrapper (`gradle/`, `gradlew*`) untracked.
- `tmp/V7__add_id_to_transactions.sql` is **staged** – would be committed.
- Tracked secrets: DB password (`compose.yaml`, both app ymls), default webhook secret in `HmacSecurityInterceptor.java:15`. `.mcp.json` has no credential keys.
- `.gitattributes` has eol rules for `gradlew` (lf) / `*.bat` (crlf); `core.autocrlf=true`.

---

## 9. Runtime smoke test — task 0.10

Run against the existing compose volumes (not a clean DB). All three apps **started** (engine 8080, ingestion 8081, AI 8082) because the dev DB already had V1–V8 recorded as successful in `flyway_schema_history` – i.e. **the dev DB was built from earlier versions of the migration files and differs from what a clean DB gets** (clean DB fails at V5, §3).

| Step | Expected | Actual |
|---|---|---|
| a) ORDER + PAYMENT, same order | 202, 202 → MATCHED pair | 202, 202 → published to `reconguard.transactions.raw` → engine insert **fails**: `null value in column "created_at" violates not-null constraint` (X4); claim evicted; offset committed; event lost |
| b) lone ORDER | UNRESOLVED → FAILED after 5 min → failure event → suggestion | insert fails (X4); Redis buffer written then expires (`WindowEvictionListener` logs "Leg remains UNRESOLVED in DB" – false, no row); sweeper runs every 60 s with nothing to sweep; failure topics, DLQ, `resolution_suggestions` all empty |
| c1) same idempotency key | duplicate | 202 `duplicate=true` ✔ |
| c2) same txId, new idempotency key | duplicate | 202 `duplicate=false`, re-published (ingestion dedups only on header; engine guard would catch it if persist worked) |
| d1) invalid JSON (signed) | 400 problem+json | **500** |
| d2) missing `monetaryAmount` | 400 | 400 validation problem ✔ |
| d3) wrong signature `deadbeef` | 401/403 | **202 ACCEPTED, published** – confirms X2 (blank signature → 401, only the null/blank check works) |

Endpoints: ingestion `/actuator/health` UP · AI `/actuator/health` UP (Ollama `nomic-embed-text`, 768 dims) · **engine `/actuator/health` 503 DOWN**, no details – engine has no actuator dependency of its own; it gets actuator + `AiWorkerHealthIndicator` transitively from `:ai-diagnostic-worker`, and in the engine that indicator uses Spring AI's default model (no `spring.ai.*` config) – likely cause, to confirm with `show-details` (0F.9) · `/actuator/prometheus` 404 on 8080 and 8082 (no registry).

Kafka topics present: `reconguard.transactions.raw`, `reconguard.transactions.failures`, `recon.failures.tenant-space-99`, `reconguard.transactions.raw.DLQ` (left over from earlier code versions; current recoverer would write `recon.dlq.default`). Redis: idempotency keys written for every request incl. the bad-signature one.

**Baseline verdict:** today no event can be persisted, so nothing downstream of the engine (matching, sweeper, failure publishing, AI, suggestions) can be exercised. The webhook accepts forged signatures.

---

## 10. Roadmap / docs vs code — tasks 0.2, 0.3

Checkbox audit (`PROJECT_ROADMAP.md`, `phase-2*.md`, `phase-3*.md`, 49 `[x]`, ~33 unique): 15 TRUE · 14 PARTIAL · 0 FALSE · 5 N/A. PARTIAL (must be un-ticked or annotated):

| Doc item | Why partial | Task |
|---|---|---|
| Tolerance comparison configured | yml keys ignored | A3.9 |
| Persist unresolved/matched | `created_at` NULL → insert fails | A3.11 |
| Add failure publishing | not consumable by AI | A2.1 |
| Engine consumer/idempotency/window/matching | persist path broken | A3.11 |
| AI embedding/search/suggestion | never receives events; 768 vs 1536 | A2.1, A1.4 |
| Idempotency key → cached 202 | no cached original response | A4.5 |
| RFC 7807 incl. HMAC | `sendError` HTML on missing header | A4.6 |
| DefaultErrorHandler + `recon.dlq.{tenant}` | header never set; docs say 3 retries, config is 15 s elapsed | A3.3 |
| V5 resolution_suggestions | migration fails | A1.1 |
| EmbeddingService OpenAI + Ollama | OpenAI starter absent; Ollama dim mismatch | A2.10, A1.4 |
| FailureContextBuilder (gateway, error code, region) | only tenant, amount, currency | G4.2 |
| MdcFilter for requests **and Kafka listeners** | HTTP only | C5 |

Prose claims contradicting code (11 HIGH, 17 MED, 7 LOW). HIGH ones:

| Doc | Claim | Reality |
|---|---|---|
| KAFKA_IMPLEMENTATION.md:18,93 | AI worker consumes engine failures | contract broken (X3) |
| AI_INTEGRATION_AND_FLOW.md:19-20 | engine produces `TransactionFailureEvent` | produces `FailurePayload` on other topic |
| INTERVIEW_GUIDE.md:20 · GENERAL.md:46,72,130 · TECHNICAL.md:15 | failure → AI suggestion works | not connected |
| INTERVIEW_GUIDE.md:39,240 · TECHNICAL.md:92-97,289 | runs on virtual threads | flag not effective |
| INTERVIEW_GUIDE_TECHNICAL.md:39-40 | Spring Security / tenant-aware auth | no security; raw header tenant |

MED themes: DLQ topic/behaviour (KAFKA:47-48,81; INTERVIEW_GUIDE:85; TECH:160), "evict claim → retry" (KAFKA:59, REDIS:10,52 – consumer swallows exceptions), gateway normalization over HTTP (KAFKA:28), Ollama/OpenAI interchangeable (AI_FLOW:45-47,116; TECH:175-181), tracing via Zipkin (INTERVIEW_GUIDE:13; TECH:236), MDC for listeners (TECH:124-125), tolerance configurable (TECH:108), results persisted (INTERVIEW_GUIDE:116; GENERAL:71).

`CLAUDE.md` itself: claims AI worker exposes `/actuator/prometheus` (no registry) and that consumers trust `io.reconguard.*` (engine yml says `io.reconguard.common.model`; AI worker has no config).
