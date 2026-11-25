# Kafka Implementation in ReconGuard

## What Kafka Is Used For
Kafka is the event backbone between ingestion and reconciliation, and between reconciliation and failure diagnostics.

Current state, it gives the project:
- asynchronous processing between the webhook and the matching engine
- loose coupling between services
- retry with backoff and a dead-letter topic in the engine
- a handoff from request handling to background work

## Where Kafka Appears
Current state:

- `ingestion-service` publishes canonical `TransactionEvent`s to `reconguard.transactions.raw`
- `reconciliation-engine` consumes `reconguard.transactions.raw`
- `reconciliation-engine` publishes a `TransactionFailureEvent` to `reconguard.transactions.failures` for unresolved legs that the sweeper marks `FAILED`
- `ai-diagnostic-worker` consumes `reconguard.transactions.failures`

## Ingestion Side
The ingestion service receives a webhook, verifies the HMAC signature, normalizes the payload, and then sends the canonical `TransactionEvent` to Kafka.

In the current code:
- HMAC verification (`X-Signature`) fails closed: a missing or bad signature is rejected before the controller runs
- the controller requires an `X-Idempotency-Key` header and claims it in Redis with SETNX (24h TTL)
- duplicates are short-circuited before publishing
- `NormalizationService.normalize(TransactionEvent)` runs on the HTTP path
- `TransactionProducer` sends the event to `reconguard.transactions.raw`, keyed by `orderId`

Planned: a duplicate key replays the original response body. The current code does not cache the original response, so a duplicate gets a status-only answer.

Planned: gateway-specific normalization (`GatewayPayload`, e.g. Stripe) on the HTTP path. The `normalize(GatewayPayload)` method exists, but the webhook endpoint accepts `TransactionEvent` and does not use it.

## Reconciliation Consumer
`TransactionConsumer` listens to `reconguard.transactions.raw` and drives the matching workflow.

Per message, the consumer does these things in order:

1. opens an MDC scope (`tenantId`, `orderId`, `transactionId`), closed on exit even when the listener throws
2. checks the Redis idempotency claim (SETNX on `reconguard:cache:idempotency:{tenant}:{txId}`)
3. binds the tenant into `TenantContext`
4. calls the matching engine

A Kafka message alone does not carry application context. The consumer reconstructs the tenant-scoped execution context before touching the database.

## Topic Design
Current topics:

- `reconguard.transactions.raw`: transaction events from ingestion
- `reconguard.transactions.raw.DLQ`: the single dead-letter topic for the raw topic. It is shared by all tenants; the tenant is carried in the record, not in the topic name
- `reconguard.transactions.failures`: `TransactionFailureEvent`s from the engine to the AI worker
- `reconguard.transactions.failures.DLT`: the AI worker's dead-letter topic for failure records

Planned: a per-tenant DLQ (`recon.dlq.{tenant}`). It is not implemented.

## Consumer Behavior
The consumer is built around reliable background processing rather than exactly-once delivery.

Current behavior:
- duplicate events are dropped early through the Redis claim
- tenant context is set per message and cleared in a `finally` block
- matching is delegated to the service layer
- on any `RuntimeException`, the consumer evicts its Redis claim and rethrows. The claim is evicted so a retry can run. The consumer does not swallow the exception, because swallowing would commit the offset and lose the event
- the engine's `DefaultErrorHandler` retries the record 3 times with exponential backoff (1s, 2s, 4s, capped at 8s), then publishes it to `reconguard.transactions.raw.DLQ`
- offsets are committed per record (`AckMode.RECORD`), only after the listener returns normally

`spring.threads.virtual.enabled` is set in each app's own `application.yml`. Whether the Kafka listener containers actually run on virtual threads is not verified.

## Why Kafka Fits The Project
Kafka is a good fit here because the system has a natural split:

- the webhook side accepts input
- the reconciliation side performs heavier business logic
- the AI worker processes failures separately

Kafka lets those stages evolve independently.

It also makes the architecture easier to explain in an interview:
“The ingestion service publishes canonical events, and the reconciliation service processes them asynchronously.”

## Error And Retry Thinking
Kafka does not solve failure handling by itself. The project layers Kafka with:
- Redis idempotency, with claim eviction on failure
- retries (3) with backoff, then a dead-letter topic
- durable persistence in PostgreSQL for unresolved legs
- per-message tenant context set and cleared

Known gap: `DeadLetterConsumer` only logs an ALERT line for DLQ records. There is no replay tooling. The engine's retry-then-DLQ path is covered by `EngineRuntimeIntegrationTest`.

## What To Mention In An Interview
If someone asks about Kafka, the strongest points are:

- it decouples ingestion from reconciliation
- it keeps request latency low
- it allows duplicate suppression and retries to be handled cleanly
- it supports downstream failure classification without blocking the webhook path

## Short Summary
Kafka is the transport layer that connects the services into one event-driven pipeline. The webhook path stays short, the engine does the matching in background consumers, and failures flow on a separate topic to the AI worker.
