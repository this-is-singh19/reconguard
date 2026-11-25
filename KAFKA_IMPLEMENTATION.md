# Kafka Implementation in ReconGuard

## What Kafka Is Used For
Kafka is the event backbone between ingestion and reconciliation, and between reconciliation and failure diagnostics.

It gives the project:
- asynchronous processing
- loose coupling between services
- retry-friendly delivery
- a clear handoff between request handling and background work

## Where Kafka Appears
Kafka is used in two main places:

- `ingestion-service` publishes canonical transaction events
- `reconciliation-engine` consumes those transaction events
- `reconciliation-engine` can also publish or route failure outcomes for downstream processing
- `ai-diagnostic-worker` consumes failure events for analysis

## Ingestion Side
The ingestion service receives a webhook, validates the request, normalizes the payload, and then sends the canonical `TransactionEvent` to Kafka.

The important implementation detail is that the service does not keep the full business workflow in the request thread. It does only the front-door work and hands the event off.

In the current code:
- the controller enforces an `X-Idempotency-Key`
- duplicates are short-circuited before publishing
- `NormalizationService` converts gateway data into the canonical event model
- `TransactionProducer` sends the event to Kafka

That means the HTTP layer stays light and the actual business processing happens downstream.

## Reconciliation Consumer
`TransactionConsumer` listens to the raw transaction topic and drives the matching workflow.

The consumer does three important things before reconciliation:

1. checks Redis idempotency
2. binds the tenant into `TenantContext`
3. calls the matching engine

This is important because a Kafka message alone does not carry application context. The consumer has to reconstruct the tenant-scoped execution context before touching the database.

## Topic Design
The project currently uses a focused topic naming style:

- `reconguard.transactions.raw` for transaction events
- `reconguard.transactions.raw.DLQ` for dead-letter handling in tests and failure scenarios

The names are simple and descriptive, which works well for a portfolio project and makes local testing easy.

## Consumer Behavior
The consumer is built around reliable background processing rather than exactly-once ceremony.

Behaviorally:
- duplicate events are dropped early through Redis
- tenant context is set per message
- matching is delegated to the service layer
- failures can trigger Redis claim eviction so later retries are possible
- the thread-local tenant context is always cleared at the end

That last point matters because Kafka listeners may reuse threads. If tenant context is not cleared, one message can leak state into another.

## Why Kafka Fits The Project
Kafka is a good fit here because the system has a natural split:

- the webhook side accepts input
- the reconciliation side performs heavier business logic
- the AI worker processes failures separately

Kafka lets those stages evolve independently.

It also makes the architecture easier to explain in an interview:
“The ingestion service publishes canonical events, and the reconciliation service processes them asynchronously.”

## Error And Retry Thinking
The implementation is not pretending Kafka solves everything by itself.

The project layers Kafka with:
- Redis idempotency
- dead-letter handling
- durable persistence in PostgreSQL
- retry-safe tenant context management

That combination is what makes the flow robust enough to describe as production-style.

## What To Mention In An Interview
If someone asks about Kafka, the strongest points are:

- it decouples ingestion from reconciliation
- it keeps request latency low
- it allows duplicate suppression and retries to be handled cleanly
- it supports downstream failure classification without blocking the webhook path

## Short Summary
Kafka is the transport layer that connects the services into one event-driven pipeline. It keeps the front-end request path simple and pushes the business reconciliation logic into background consumers where it belongs.
