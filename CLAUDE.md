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

./gradlew :ingestion-service:bootRun                # port 8081
./gradlew :reconciliation-engine:bootRun            # port 8080, runs Flyway migrations
./gradlew :ai-diagnostic-worker:bootRun             # port 8082, actuator/prometheus at /actuator
```

There is no lint or formatter task. The `integrationTest`, `flywayMigrate` and `:modules:*` tasks mentioned in `architecture` and other planning docs do not exist.

Test setups by module:
- `reconciliation-engine`: uses `@EmbeddedKafka` plus H2 in PostgreSQL mode (`application-test.yml`, profile `test`, Flyway disabled, `ddl-auto=create-drop`). Redis is mocked with `@MockBean`.
- `ai-diagnostic-worker`: uses Testcontainers (`pgvector/pgvector:pg16`, `cp-kafka`), so Docker must be running.

## Architecture

### Modules
- **common**: plain jar with no Boot plugin. It holds shared records (`TransactionEvent`, `MonetaryAmount`, `TransactionEventType`), `TransactionFailureEvent`, `TenantContext` (ThreadLocal) with `TenantInterceptor` (reads the `X-Tenant-Id` header), and the logging `MdcFilter`/`MaskingConverter`. Its `application.yml` turns on virtual threads.
- **ingestion-service**: `POST /api/v1/webhooks/transactions`. Two interceptors guard this path, registered in `WebMvcConfig` and run in order:
  1. `HmacSecurityInterceptor` checks the `X-Signature` header against the body. The body is cached by `RequestCachingFilter`. The secret is `reconguard.webhook.secret`.
  2. `TenantInterceptor` sets the tenant.

  The controller then deduplicates on `X-Idempotency-Key` using Redis SETNX with a 24h TTL. It normalizes the event and publishes it to Kafka topic `reconguard.transactions.raw`, keyed by `orderId`.
- **reconciliation-engine**: `TransactionConsumer` consumes `reconguard.transactions.raw` and goes through these steps:
  1. `IdempotencyGuard` does a Redis SETNX on `reconguard:cache:idempotency:{tenant}:{txId}`. The claim is evicted if processing fails.
  2. The consumer sets `TenantContext`.
  3. It calls `MatchingEngineService.reconcile`, which looks for the counterpart leg in two places. First it checks the Redis window buffer (`WindowBufferService`, key `reconguard:window:buffer:{tenant}:{orderId}`, 35s TTL). If nothing is there, it checks Postgres for UNRESOLVED legs.

  A match needs opposite ORDER/PAYMENT types, the same currency, an amount within `reconguard.topics.matching.amount-tolerance`, and timestamps within `timestamp-tolerance` (bound by `MatchingToleranceConfig`). Unmatched legs are saved as `UNRESOLVED` and buffered in Redis.

  `WindowUnresolvedTransactionSweeper` (`@Scheduled`) marks UNRESOLVED legs older than 5 minutes as `FAILED` and emits them through `FailurePublisher`. `KafkaConsumerConfig` sets up the `DefaultErrorHandler`: exponential backoff, then DLQ topic `recon.dlq.{X-Tenant-Id header}`.

  This module owns all **Flyway migrations** (`src/main/resources/db/migration`), including the `known_issues`/`resolution_suggestions` tables and their seed data used by the AI worker. Add new migrations here.
- **ai-diagnostic-worker**: consumes `TransactionFailureEvent` from `reconguard.transactions.failures` and runs this pipeline:
  1. `FailureContextBuilder` turns the event into text.
  2. `EmbeddingService` creates an embedding.
  3. `VectorSimilaritySearchService`/`VectorSearchRepository` runs a pgvector cosine top-K search over `known_issues`.
  4. `ResolutionSuggestionBuilder` classifies the top score: ≥0.90 is `AUTO_RESOLVED`, ≥0.75 is `AUTO_CLASSIFIED`, anything lower is `NEEDS_REVIEW`.
  5. The result is saved to `resolution_suggestions`.

  `reconguard.ai.provider` (`ollama` | `openai`) picks the embedding implementation through `@ConditionalOnProperty`. Spring AI's Ollama starter supplies the model (`nomic-embed-text`). JPA is set to `ddl-auto: validate`, so the schema must already exist from the reconciliation-engine migrations. Micrometer metrics live in `AiDiagnosticMetrics`. The Grafana dashboard JSON is in `grafana/dashboards/`.

### Cross-module gotchas
- `reconciliation-engine` has `implementation project(':ai-diagnostic-worker')` and scans, entity-scans and repository-scans all of `io.reconguard`. As a result, the AI worker's beans (Kafka listeners, embedding service, JPA entities) are also loaded inside the reconciliation-engine app. Keep this in mind when you add beans or required properties to the AI worker. `reconguard.ai.provider` is set in both modules' yml for this reason.
- **The failure topic contract is inconsistent.** `FailurePublisher` sends a `FailurePayload(TransactionEvent, reason)` to `recon.failures.{tenantId}`. The AI worker listens on `reconguard.transactions.failures` and expects `TransactionFailureEvent`. Two listeners consume that topic: `TransactionFailureConsumer` and `DiagnosticEventListener`. `UnresolvedTransactionSweeperTest` asserts `reconguard.transactions.failures`. Check which side is intended before you change either one.
- `TransactionConsumer` has a temporary DLQ test hook: any `transactionId` starting with `FAIL-TEST` throws on purpose.
- Kafka JSON uses type headers (`spring.json.add.type.headers: true`). Consumers trust `io.reconguard.*`. If you move or rename event records, the wire format breaks.
- Connection credentials for local dev are hardcoded in each module's `application.yml` and match `compose.yaml`.

## Docs in the repo

Use the file PROJECT_COMPLETION_TASKS.md file to list , track and update tasks for this project.