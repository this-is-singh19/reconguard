# ReconGuard Interview Guide

## What This Project Is
ReconGuard is a Spring Boot, multi-module reconciliation system built to process payment events, match related transaction legs, and classify failures with an AI-assisted worker.

At a high level, it shows a realistic event-driven backend with:
- webhook ingestion
- canonical event normalization
- Kafka-based transport
- Redis idempotency and buffering
- PostgreSQL persistence with Flyway migrations
- pgvector-based similarity search for failure analysis
- Micrometer tracing and structured logging for observability

The project is intentionally portfolio-sized, but the moving parts are close to what a real production system would use.

## How I Would Explain It In An Interview
If I had to explain ReconGuard in 30 to 60 seconds, I would say:

“ReconGuard ingests payment webhooks, normalizes them into a canonical transaction event, and sends them through Kafka to a reconciliation engine. The engine uses Redis to prevent duplicate processing and to hold a short matching window, then persists reconciliation results in PostgreSQL. If a transaction cannot be matched cleanly, a failure event is published to an AI worker that looks up similar known issues with pgvector and proposes a resolution suggestion. The system is split into modules so each concern stays readable: ingestion, reconciliation, shared contracts, and AI diagnostics.”

That version is short enough for a first answer, but it still sounds like a system you built deliberately.

## Architecture At A Glance
The current codebase is organized around four modules:

- `common`: shared contracts, tenant context, logging helpers, and security helpers
- `ingestion-service`: receives and validates webhooks, normalizes payloads, and publishes transaction events
- `reconciliation-engine`: consumes events, applies idempotency and matching rules, and persists reconciliation state
- `ai-diagnostic-worker`: consumes failure events, compares them against known issues, and generates resolution suggestions

Infrastructure in `compose.yaml` supports the runtime story:
- PostgreSQL for durable data
- Redis for idempotency and short-lived state
- Kafka for asynchronous event transport
- Ollama for local embedding backends
- Zipkin for tracing

The architecture guide and roadmap favor blocking Spring MVC plus virtual threads instead of WebFlux. That is a good interview point: the code stays easier to explain, while still allowing high concurrency for blocking I/O.

## Module Breakdown

### `common`
This is the shared foundation.

It contains:
- `TransactionEvent` and related canonical event types
- `TransactionFailureEvent` for downstream diagnostics
- tenant context helpers
- `MdcFilter` for correlation and tenant logging
- `MaskingConverter` for sensitive log redaction
- HMAC and security-related exceptions and helpers

Interview angle:
- It keeps cross-cutting concerns in one place.
- It avoids copy-pasting event contracts into each service.
- It gives the rest of the system a stable vocabulary.

### `ingestion-service`
This is the entry point for webhook traffic.

It currently includes:
- webhook controller handling
- HMAC verification
- request caching for idempotent webhook handling
- normalization from gateway-specific payloads to canonical transaction events
- Kafka publishing
- global exception handling

Interview angle:
- The service isolates external payload shape from the rest of the system.
- It validates authenticity before doing downstream work.
- It treats duplicate webhook submissions as a first-class case rather than an error after the fact.

### `reconciliation-engine`
This is the core matching service.

It currently includes:
- Kafka consumer logic
- Redis idempotency guard
- Redis window buffer
- matching engine and tolerance-based comparison
- database repository layer
- unresolved transaction sweep and failure publication pieces
- a dead-letter consumer path

Interview angle:
- This is where event-driven processing becomes business logic.
- Redis handles short-lived coordination, while PostgreSQL holds durable truth.
- Matching uses tolerance-based rules rather than naive exact equality, which is more realistic for financial systems.

### `ai-diagnostic-worker`
This is the diagnostic and suggestion layer.

It currently includes:
- embedding service abstraction
- OpenAI and Ollama embedding implementations
- failure context building
- vector similarity search using pgvector
- resolution suggestion classification
- metrics and health endpoints

Interview angle:
- The AI layer is not generic “AI everywhere.”
- It has one narrow job: use historical patterns to classify reconciliation failures.
- The embedding backend is swappable, which is a strong design choice for local development and production flexibility.

## Data Flow
The easiest way to describe the system is as a pipeline.

1. A gateway sends a webhook into `ingestion-service`.
2. The service verifies the request, normalizes the payload, and publishes a canonical `TransactionEvent` to Kafka.
3. `reconciliation-engine` consumes the event.
4. Redis checks prevent duplicate processing, and Redis buffering keeps a short reconciliation window open.
5. The engine matches related transaction legs using configured amount and timestamp tolerance.
6. Matched or unresolved results are persisted in PostgreSQL.
7. If a failure cannot be resolved, a failure event is published.
8. `ai-diagnostic-worker` consumes the failure, builds structured context, searches similar known issues, and stores a resolution suggestion.

That flow is worth memorizing because it lets you answer both “what does it do?” and “how does data move through it?”

## Why These Design Choices

### Kafka instead of synchronous calls
Kafka decouples ingestion from reconciliation.

Why that matters:
- ingestion can respond quickly
- reconciliation can retry independently
- downstream failures do not block webhook acceptance
- the system is easier to scale by consumer group

### Redis for idempotency and window buffering
Redis is used where low-latency, short-lived state matters.

Why that matters:
- webhook duplicates can be rejected quickly
- reconciliation can hold a short pairing window without hammering the database
- TTL-based state fits the matching problem well

### PostgreSQL and Flyway for durable truth
This is the durable source of record.

Why that matters:
- business data is queryable and auditable
- schema changes are explicit
- the project has a clean migration story

### pgvector for known-issue matching
The AI worker is not trying to “understand” failures from scratch.

Instead, it compares failure context against known patterns and ranks likely matches.

Why that matters:
- it is explainable
- it works well for portfolio-scale diagnostic workflows
- it keeps the AI part grounded in searchable history

### Blocking MVC plus virtual threads
This is a practical architecture choice.

Why that matters:
- the code is easier to follow than a fully reactive stack
- blocking libraries remain usable
- virtual threads reduce the penalty of blocking I/O without making the codebase harder to teach

## What Is Already Good To Talk About
These are the strongest interview talking points in the current codebase:

- multi-module separation with a shared contract layer
- canonical event modeling
- webhook normalization and HMAC verification
- idempotency at multiple layers
- Redis buffering for reconciliation windows
- tolerance-based matching instead of exact match logic
- AI-assisted failure classification using vector similarity
- structured logging, masking, and correlation IDs
- tracing and metrics groundwork

## Tradeoffs And Risks
Good interview answers acknowledge tradeoffs.

- The schema and entity model still need final alignment in a few places, especially around the reconciliation transaction record shape.
- Some phase items are intentionally partial, especially around runtime tenant provisioning and the full observability dashboard stack.
- Redis window state is intentionally short-lived, so a crash can lose in-flight buffering state even though the durable database still protects the system overall.
- The AI worker is only as useful as the baseline issue data and embeddings quality.
- A portfolio project needs enough realism to be credible, but not so much infrastructure that the explanation becomes confusing.

## Phase Story
The roadmap tells a useful story in an interview.

### Phase 0
Repository and architecture alignment.

This phase established the module structure, shared `common` library, and local infrastructure direction.

### Phase 1
Ingestion and Kafka setup.

This phase introduced the webhook entry point, canonical event modeling, and the event transport layer.

### Phase 2
Reconciliation engine and Redis idempotency.

This phase is the heart of the matching system: consuming events, gating duplicates, buffering windows, matching pairs, and persisting outcomes.

### Phase 2.5
Ingestion and reconciliation gaps.

This phase closes the engineering gaps between the guide and the implementation, such as response contracts, normalization, tenant propagation, DLQ handling, and schema cleanup.

### Phase 3
AI utility integration and observability.

This phase adds the failure classification worker, vector search, logs, traces, and metrics.

### Phase 4 and 5
Hardening and deployment.

These are the polish phases: rate limiting, management APIs, dashboards, test coverage, CI/CD, and deployment artifacts.

## Interview Answers I Would Prepare

### “Why did you split the system into modules?”
Because it makes the system easier to understand and changes safer to make.

The shared `common` module keeps contracts and cross-cutting helpers stable, while each service owns one responsibility.

### “Why not do everything in one app?”
Because the domain naturally separates into ingestion, reconciliation, and diagnostics.

If everything lived together, the event contracts, matching logic, and AI logic would be harder to reason about and test independently.

### “Why use Redis if PostgreSQL is already there?”
Because not all state belongs in the database.

Idempotency checks and short matching windows are latency-sensitive and ephemeral, so Redis is a better fit than writing every intermediate step to PostgreSQL.

### “Why virtual threads?”
Because this project uses blocking libraries heavily, and virtual threads let the code stay simple without giving up concurrency.

### “Where does the AI fit?”
Only after a failure is detected.

The AI worker does not replace reconciliation logic. It assists with classification and resolution suggestions after the core business workflow has already done its job.

## What I Would Improve Next
If I were continuing the project, the next useful improvements would be:

- finish schema and entity alignment
- close the remaining reconciliation failure publication gaps
- add focused integration tests for duplicates, tenant isolation, and timeout paths
- seed more known issues for the AI worker
- wire tracing fully into Zipkin and add the missing metrics dashboards
- finish the runtime tenant provisioning story

## Short Closing Summary
ReconGuard is a good interview project because it demonstrates more than CRUD.

It shows:
- event-driven design
- multi-service boundaries
- resilience patterns
- persistence and migration discipline
- observability
- AI-assisted diagnostics with a clear scope

If I had to summarize the technical level in one sentence, I would say this is a practical Spring Boot system that demonstrates how to move from raw webhook input to reliable reconciliation and then to explainable failure analysis.
