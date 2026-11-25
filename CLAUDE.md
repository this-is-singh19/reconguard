# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

be concise and ignore grammar when responding to me. always use haiku subagents when the tasks involves mechanical stuffs like starting up service / validation or similar things.

## What this is

ReconGuard: a multi-tenant financial reconciliation system. Order and payment events come in, get paired within tolerance windows, and pairs that never match are sent to an AI worker. The worker classifies each failure by vector similarity against known issues stored in pgvector.

Gradle multi-module build, Java 21 toolchain, Spring Boot 3.5.x. Base package is `io.reconguard`.

## Commands

Run these from the repo root. On Windows use `.\gradlew.bat` in PowerShell, or `./gradlew` in Git Bash.

```bash
docker compose up -d                                # Postgres(pgvector) 5432, Redis 6379, Kafka(KRaft) 9092, Ollama 11434, Zipkin 9411
docker exec -it reconguard-ollama ollama pull nomic-embed-text   # embedding model the AI worker needs

./gradlew build                                     # build + test all modules
./gradlew build -x test
./gradlew :reconciliation-engine:test
./gradlew :reconciliation-engine:test --tests "io.reconguard.reconciliation.service.MatchingEngineServiceTest"
./gradlew :ingestion-service:test --tests "*WebhookControllerTest.someMethod"

./gradlew :ingestion-service:bootRun                # port 8081 (needs reconguard.webhook.secret set)
./gradlew :reconciliation-engine:bootRun            # port 8080, runs Flyway migrations
./gradlew :ai-diagnostic-worker:bootRun             # port 8082, actuator at /actuator (no /actuator/prometheus yet, see below)
```

There is no lint or formatter task. The `integrationTest`, `flywayMigrate` and `:modules:*` tasks mentioned in `architecture` and other planning docs do not exist.

Test setups by module:
- `reconciliation-engine`: uses `@EmbeddedKafka` plus H2 in PostgreSQL mode (`application-test.yml`, profile `test`, Flyway disabled, `ddl-auto=create-drop`).
- `ai-diagnostic-worker`: uses Testcontainers (`pgvector/pgvector:pg16`, `cp-kafka`), so Docker must be running.

## Architecture

### Modules
- **common**: plain jar with no Boot plugin and no `application.yml`. It holds shared records (`TransactionEvent`, `MonetaryAmount`, `TransactionEventType`), `TransactionFailureEvent`, `TenantContext` (ThreadLocal) with `TenantInterceptor` (reads the `X-Tenant-Id` header), and the logging `MdcFilter`/`MaskingConverter`.
- **ingestion-service**: `POST /api/v1/webhooks/transactions`. Two interceptors guard this path, registered in `WebMvcConfig` and run in order:
  1. `HmacSecurityInterceptor` verifies the `X-Signature` HMAC against the body and fails closed. The body is cached by `RequestCachingFilter`. The secret is `reconguard.webhook.secret`, has no default, and startup fails if it is blank.
  2. `TenantInterceptor` sets the tenant.

  The controller then deduplicates on `X-Idempotency-Key` using Redis SETNX with a 24h TTL. It normalizes the event and publishes it to Kafka topic `reconguard.transactions.raw`, keyed by `orderId`.
- **reconciliation-engine**: `TransactionConsumer` consumes `reconguard.transactions.raw` and goes through these steps:
  1. `IdempotencyGuard` does a Redis SETNX on `reconguard:cache:idempotency:{tenant}:{txId}`. The claim is evicted if processing fails.
  2. The consumer sets `TenantContext`.
  3. It calls `MatchingEngineService.reconcile`, which looks for the counterpart leg in two places. First it checks the Redis window buffer (`WindowBufferService`, key `reconguard:window:buffer:{tenant}:{orderId}`, 35s TTL). If nothing is there, it checks Postgres for UNRESOLVED legs.

  A match needs opposite ORDER/PAYMENT types, the same currency, an amount within `reconguard.matching.max-amount-difference` (0.02), and timestamps within `reconguard.matching.max-time-window` (120s). Both are bound by `MatchingToleranceConfig`. Unmatched legs are saved as `UNRESOLVED` and buffered in Redis.

  `WindowUnresolvedTransactionSweeper` (`@Scheduled`) marks UNRESOLVED legs older than 5 minutes as `FAILED` and emits them through `FailurePublisher`.

  Retries and DLQ: `KafkaConsumerConfig` sets up the `DefaultErrorHandler` with exponential backoff. Exhausted records go to a single shared DLQ topic, `reconguard.transactions.raw.DLQ` (source topic + `.DLQ`), read by `DeadLetterConsumer`. There is no per-tenant DLQ.

  Scanning: `ReconciliationApplication` scans `io.reconguard` but excludes `io.reconguard.ai.*`, `io.reconguard.health.*`, `io.reconguard.controller.*` and `AiWorkerApplication`. JPA entities and repositories are scanned only in `io.reconguard.reconciliation`.

  Virtual threads are enabled per app (`spring.threads.virtual.enabled` in each app's `application.yml`), not in `common`.

  This module owns all **Flyway migrations** (`src/main/resources/db/migration`), including the `known_issues`/`resolution_suggestions` tables and their seed data used by the AI worker. Add new migrations here.
- **ai-diagnostic-worker**: consumes `TransactionFailureEvent` from `reconguard.transactions.failures` (`reconguard.topics.failed-transactions`) and runs this pipeline:
  1. `FailureContextBuilder` turns the event into text.
  2. `EmbeddingService` creates an embedding. `OllamaEmbeddingService` is the only implementation and is selected by `@ConditionalOnProperty(reconguard.ai.provider=ollama)`. Spring AI's Ollama starter supplies the model (`nomic-embed-text`).
  3. `VectorSimilaritySearchService`/`VectorSearchRepository` runs a pgvector cosine top-K search over `known_issues`.
  4. `ResolutionSuggestionBuilder` classifies the top score: ≥0.90 is `AUTO_RESOLVED`, ≥0.75 is `AUTO_CLASSIFIED`, anything lower is `NEEDS_REVIEW`.
  5. The result is saved to `resolution_suggestions`.

  Embedding dimension is 768 (`reconguard.ai.embedding.dimension`). `EmbeddingDimensionCheck` fails startup if it does not match `known_issues.embedding`. `KnownIssueEmbeddingBootstrap` computes `known_issues` embeddings at startup.

  JPA is set to `ddl-auto: validate`, so the schema must already exist from the reconciliation-engine migrations. Micrometer metrics live in `AiDiagnosticMetrics`. The Grafana dashboard JSON is in `grafana/dashboards/`. `/actuator/prometheus` is not served: `micrometer-registry-prometheus` is not a dependency, so the endpoint is absent even though it is listed in `management.endpoints.web.exposure.include`.

### Failure contract
- The engine's `FailurePublisher` sends a `TransactionFailureEvent` to `reconguard.transactions.failures`, keyed `{tenantId}:{orderId}`. The AI worker's `TransactionFailureConsumer` reads it with `FailureKafkaConsumerConfig`. This is the only failure contract.

### Cross-module gotchas
- `reconciliation-engine` has `implementation project(':ai-diagnostic-worker')`, so the AI worker's classes are on its classpath. The engine excludes the AI packages from its component scan (see above). Keep this in mind when you add beans or required properties to the AI worker. `reconguard.ai.provider` is set in both modules' yml. The engine sets `spring.ai.model.chat/embedding: none` so it never builds a model.
- Kafka JSON uses type headers (`spring.json.add.type.headers: true`, set in the ingestion and engine yml). Deserialization trusted packages are set in Java: the engine trusts `io.reconguard.common.model`, the AI worker trusts `io.reconguard.common.event`. If you move or rename event records, the wire format and trusted packages break.
- Connection credentials for local dev are hardcoded in each module's `application.yml` and match `compose.yaml`. The webhook secret is not hardcoded (see ingestion-service).

## Docs in the repo

Use the file PROJECT_COMPLETION_TASKS.md file to list , track and update tasks for this project.
