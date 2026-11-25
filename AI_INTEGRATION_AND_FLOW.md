# AI Integration and Flow in ReconGuard

## What The AI Layer Does
The AI part of ReconGuard does not replace reconciliation.

Its job is narrower:
- consume unresolved or failed transaction events
- build a structured failure context
- generate an embedding for that context
- compare it against known issues
- produce a ranked diagnostic result or suggestion

This keeps the AI layer practical and explainable.

## Where It Sits In The Flow
The flow is:

1. reconciliation detects a failure or unresolved case
2. a `TransactionFailureEvent` is produced
3. `ai-diagnostic-worker` consumes the failure
4. `FailureContextBuilder` creates a text summary of the failure
5. `EmbeddingService` converts the summary into a vector
6. `VectorSimilaritySearchService` queries known issues using pgvector
7. the worker returns matches or stores a resolution suggestion

That means the AI layer is a downstream analysis step, not the primary business decision engine.

## Main Classes Involved

### `FailureContextBuilder`
This class takes the raw failure event and turns it into structured text.

The goal is to include the details that matter for similarity search, such as:
- tenant
- amount
- currency
- gateway
- error or failure metadata

The text is structured so the embedding model gets consistent input.

### `EmbeddingService`
This is the abstraction over embedding generation.

The project supports two implementations:
- OpenAI for a cloud-backed setup
- Ollama for local or air-gapped development

That makes the AI layer switchable without changing the rest of the application code.

### `VectorSimilaritySearchService`
This service performs the vector lookup.

It sends the embedding to the repository layer and searches the `known_issues` table using pgvector similarity.

The repository returns the top matches, ordered by similarity.

### `DiagnosticWorkerService`
This is the orchestration service for the AI flow.

It wires together:
- context building
- embedding generation
- vector search

It is the class that makes the flow easy to follow from end to end.

## Retrieval-Based Design
The AI system is retrieval-first rather than generation-first.

That means:
- historical issues are stored in the database
- failures are embedded into vector space
- similar cases are retrieved
- the suggestion is based on the closest known patterns

This is easier to justify in interviews than an open-ended generative system because the output is tied to concrete historical examples.

## Why pgvector Is Used
pgvector lets the application search for semantic similarity directly in PostgreSQL.

That gives the worker:
- a simple storage model
- one durable database for known issues
- fast similarity search with indexed vector data

The project currently uses cosine similarity and top-K lookup.

## Flow Of A Failure Event
A clean interview explanation is:

- reconciliation cannot match a transaction cleanly
- the failure is turned into a failure event
- the AI worker builds a descriptive context
- embeddings are created from that context
- the system searches for related known issues
- the best match is used to classify or suggest a resolution

That is the complete flow without getting lost in implementation detail.

## Operational Pieces
The AI module also includes operational support:

- metrics collection for search and diagnostic work
- health endpoint support
- swappable embedding providers
- repository-backed retrieval of known issues and suggestions

This matters because the AI part should be observable and easy to debug, not just “smart.”

## What To Mention In An Interview
Strong talking points include:

- AI is used only after a failure is detected
- embeddings and vector search make the suggestion explainable
- OpenAI and Ollama are interchangeable through configuration
- the worker is intentionally narrow in scope
- the approach keeps the system practical and portfolio-ready

## Short Summary
The AI integration is a downstream diagnostic pipeline that turns reconciliation failures into structured context, compares them with known issues using embeddings and pgvector, and produces a useful suggestion without changing the core reconciliation logic.
