# ReconGuard Technical Interview Guide

## Purpose
This document is the deep technical version of the ReconGuard interview narrative.

It is meant for:
- system design follow-ups
- backend architecture interviews
- discussions with engineers who want implementation detail
- questions about tradeoffs, failure modes, and data flow

The goal is to explain not just what the project does, but how the pieces fit together and why the design is structured this way.

## One-Sentence Summary
ReconGuard is a Spring Boot, multi-module, event-driven reconciliation platform that ingests payment webhooks, normalizes them into canonical events, matches transaction legs with Redis-backed idempotency and buffering, persists durable reconciliation state in PostgreSQL, and classifies unresolved failures with an AI worker that uses pgvector similarity search.

## System Architecture
The system is split into four main modules:

- `common`: shared event contracts, tenant context, logging utilities, and security helpers
- `ingestion-service`: webhook intake, HMAC verification, payload normalization, and Kafka publishing
- `reconciliation-engine`: Kafka consumption, duplicate suppression, window buffering, matching, persistence, and failure publication
- `ai-diagnostic-worker`: failure consumption, embedding generation, similarity search, and resolution suggestion creation

This split is deliberate. It keeps the core domain flow readable and prevents the AI, transport, and persistence concerns from being tangled together.

## Infrastructure Dependencies
Local infrastructure is defined in `compose.yaml` and supports the runtime model:

- PostgreSQL for durable business state
- Redis for idempotency and short-lived matching state
- Kafka for asynchronous event transport
- Ollama for local embedding execution
- Zipkin for tracing

The broader architecture also assumes:
- Flyway for schema evolution
- Micrometer for metrics and tracing
- Spring Security and tenant-aware context propagation
- blocking Spring MVC with virtual threads rather than WebFlux

## Architectural Rationale

### Why a multi-module layout
Each module represents a distinct responsibility:

- ingress and normalization
- reconciliation and persistence
- AI-based diagnostics
- shared contracts and cross-cutting concerns

This is better than a monolith with package-only separation because the boundaries are visible in the build, the deployment story, and the code ownership model.

### Why canonical events
External gateways do not all speak the same schema. ReconGuard converts those variations into a canonical `TransactionEvent` so downstream services only reason about one contract.

That design reduces coupling and makes reconciliation logic stable even when gateway-specific payloads change.

### Why Kafka
Kafka is the backbone between ingestion and reconciliation.

It provides:
- asynchronous decoupling
- retry-friendly processing
- independent scaling of consumer groups
- a clean boundary between external request handling and internal business processing

The project is not using synchronous service-to-service calls for the main flow because the domain naturally fits an event pipeline.

### Why Redis
Redis is used for two short-lived concerns:

- idempotency checks
- reconciliation window buffering

Those concerns are high-frequency and TTL-based, so Redis is more appropriate than forcing every intermediate state into PostgreSQL.

### Why PostgreSQL and Flyway
PostgreSQL stores the durable system of record.

Flyway makes the schema explicit and auditable. That matters in an interview because it shows the project is not just logic-heavy; it also has a disciplined data model.

### Why pgvector
The AI worker does not try to invent a generic diagnostic brain.

Instead, it embeds failure context and compares it against known issues. This is a realistic retrieval-based workflow:

- it is explainable
- it is maintainable
- it works well for a portfolio-scale diagnostic system

### Why blocking MVC with virtual threads
The codebase intentionally avoids a reactive stack.

That choice makes the services easier to read and explain while still giving the runtime better concurrency characteristics for blocking I/O.

For an interview, this is a strong point because it shows a conscious tradeoff rather than a trend-driven choice.

## End-to-End Data Flow
The simplest technical walkthrough is:

1. A payment gateway sends a webhook to `ingestion-service`.
2. The request is authenticated with HMAC and passed through idempotency handling.
3. The payload is normalized into a canonical transaction event.
4. The event is published to Kafka.
5. `reconciliation-engine` consumes the event.
6. Redis blocks duplicate processing and buffers the event for a short matching window.
7. Matching logic compares amount and timestamp using configured tolerance, not strict equality.
8. Reconciliation outcomes are persisted in PostgreSQL.
9. If the event cannot be matched cleanly, a failure event is emitted.
10. `ai-diagnostic-worker` consumes the failure, creates structured failure context, searches known issues with pgvector, and stores a resolution suggestion.

That flow is the backbone of the entire project.

## Module Deep Dive

### `common`
This module holds cross-cutting types and utilities.

Important responsibilities:
- canonical event models such as `TransactionEvent`
- failure event contracts such as `TransactionFailureEvent`
- tenant context management
- request and listener correlation in MDC
- masking of sensitive data before logs are written
- HMAC and shared security exceptions

Why it matters:
- it prevents each service from redefining the same concepts
- it keeps logging and tenant behavior consistent
- it gives every downstream module a stable contract layer

### `ingestion-service`
This module receives the outside world.

Important responsibilities:
- webhook controller
- HMAC verification
- request caching for duplicate webhook submissions
- payload normalization from gateway-specific shapes to canonical events
- Kafka production
- centralized exception mapping

Current interview-worthy points:
- duplicate submissions are treated as an idempotency concern, not as a generic failure
- request authenticity is checked before downstream processing
- normalization happens before Kafka publication so downstream systems are shielded from gateway-specific schemas

### `reconciliation-engine`
This module contains the main reconciliation workflow.

Important responsibilities:
- Kafka consumption
- Redis idempotency gating
- Redis reconciliation window buffering
- tolerance-based matching
- unresolved transaction sweep
- failure publication
- persistence through JPA/PostgreSQL
- dead-letter handling for consumer failures

The important design idea here is that matching logic should remain as pure as possible.

Infrastructure should orchestrate around it:
- Kafka brings events in
- Redis gates and buffers
- the database records durable outcomes
- failure events are emitted for unresolved cases

### `ai-diagnostic-worker`
This module handles failure diagnosis, not general AI features.

Important responsibilities:
- failure event consumption
- embedding generation through a swappable backend
- failure context assembly
- similarity search against known issues using pgvector
- classification and suggestion generation
- metrics and health endpoints

The project currently supports both OpenAI and Ollama embedding implementations. That is useful because it shows production/local portability without changing application code.

## Matching and Reconciliation Logic
This part is often the strongest technical discussion point.

The matching engine does not compare raw events by exact equality. It uses configurable tolerance around:
- monetary amount
- event timestamp

That is more realistic for financial reconciliation because real-world systems can differ by small timing offsets or rounding behavior.

Redis participates in two ways:

- `SETNX`-style idempotency gating prevents the same event from being processed multiple times
- a short TTL window buffers one side of a transaction pair while the counterpart arrives

Once a match is found, the durable outcome is stored in PostgreSQL. If no match is found within the allowed window, the unresolved path is made explicit rather than hidden.

## Failure Handling
The failure path is intentionally first-class.

The architecture includes:
- dead-letter behavior for consumer failures
- unresolved transaction persistence
- failure event publication
- downstream AI classification

That means the system is not pretending every message will reconcile cleanly. Instead, it models failure as part of the domain.

## AI Diagnostic Design
The AI worker is intentionally narrow.

It takes a failure, builds structured context, and compares it against known issues. It is not trying to make arbitrary decisions across the whole platform.

The AI flow is:

1. consume failure event
2. build structured context
3. generate embeddings
4. query `known_issues` using vector similarity
5. rank candidate matches
6. produce a resolution suggestion

Why this is a strong design:
- it is explainable in interviews
- it gives a concrete use of AI instead of a vague one
- it is easy to extend with better seed data and observability

## Observability
Observability is part of the architecture, not an afterthought.

Current concepts in the project:
- MDC correlation IDs
- tenant IDs in logs
- masking of sensitive fields
- tracing support through Zipkin
- Micrometer metrics for workflow visibility

Why this matters:
- reconciliation systems are hard to debug without traceability
- failure classification is more credible when it is observable
- logging and tracing need to carry tenant context consistently

## Schema and Data Model Topics Worth Knowing
The roadmap highlights a few technical issues that are good to talk about:

- transaction schema alignment still needs final verification
- unresolved records are intentionally persisted rather than discarded
- phase 3 needs a durable schema foundation for known issues and resolution suggestions
- tenant provisioning is a real design choice, not just a deployment detail

This is useful in interviews because it shows awareness of schema drift and operational ownership, not just application code.

## Tradeoffs And Risks
Strong interview answers should mention tradeoffs explicitly.

- Redis state is short-lived, so the system accepts that in-flight buffered data can disappear on a crash while durable records remain in PostgreSQL.
- The AI worker depends on the quality and coverage of known-issue seed data.
- The project is intentionally portfolio-sized, so some production hardening work is planned but not fully completed.
- The current design favors clarity and explainability over maximal framework complexity.

## What To Emphasize In A Deep Dive
If someone asks follow-up questions, these are the strongest talking points:

- canonical event normalization
- HMAC verification and duplicate webhook handling
- Kafka as the event boundary
- Redis as a low-latency coordination layer
- tolerance-based matching
- durable persistence with Flyway-backed schema changes
- failure publication and dead-letter handling
- pgvector-based retrieval for AI classification
- observability with MDC, masking, and tracing

## Good Interview Questions And Strong Answers

### Why did you choose an event-driven architecture?
Because the workflow naturally spans ingestion, matching, persistence, and diagnostics, and those steps do not need to block each other.

### Why not let the ingestion service do the reconciliation itself?
That would tightly couple external request handling to business processing and make retries, scaling, and failure isolation worse.

### Why use Redis and PostgreSQL together?
Redis is for transient coordination; PostgreSQL is for durable truth.

### Why use pgvector instead of a rules-only diagnostic system?
Because the AI worker benefits from similarity search over known failure patterns, and vector search is a good fit for that problem.

### Why is virtual-threaded blocking MVC acceptable here?
Because the system uses blocking libraries heavily and the code is easier to reason about in that model.

## Current Gaps To Mention Honestly
These gaps are worth being transparent about in an interview:

- schema and entity alignment still needs final cleanup in some areas
- some failure publishing and integration testing is still being completed
- runtime tenant provisioning is planned rather than fully finished
- observability dashboards are still part of the roadmap

Being clear about these gaps makes the project sound more real, not less.
