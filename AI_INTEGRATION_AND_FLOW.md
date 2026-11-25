# AI Integration and Flow in ReconGuard

## What The AI Layer Does
The AI part of ReconGuard does not replace reconciliation.

Its job is narrower:
- consume failed transaction events
- build a structured failure context
- generate an embedding for that context
- compare it against known issues
- classify the result and save a resolution suggestion

This keeps the AI layer practical and explainable.

## Where It Sits In The Flow
Current state, the failure pipeline works end to end:

1. the engine's matching flow leaves a leg unmatched and saves it as `UNRESOLVED`
2. `WindowUnresolvedTransactionSweeper` marks legs older than 5 minutes as `FAILED`
3. `FailurePublisher` sends a `TransactionFailureEvent` to `reconguard.transactions.failures`
4. `ai-diagnostic-worker` consumes it in `TransactionFailureConsumer`
5. `FailureContextBuilder` creates a text summary of the failure
6. `EmbeddingService` (Ollama) converts the summary into a vector
7. `VectorSimilaritySearchService` queries `known_issues` using pgvector
8. `ResolutionSuggestionBuilder` classifies the top score and the result is saved to `resolution_suggestions`

Score thresholds: at least 0.90 is `AUTO_RESOLVED`, at least 0.75 is `AUTO_CLASSIFIED`, anything lower is `NEEDS_REVIEW`.

That means the AI layer is a downstream analysis step, not the primary business decision engine.

## Main Classes Involved

### `FailureContextBuilder`
This class takes the raw failure event and turns it into structured text.

The text currently includes only tenant, amount, and currency. Gateway, error code, and region are carried on the event but not yet used in the text (planned).

The text is structured so the embedding model gets consistent input.

### `EmbeddingService`
This is the abstraction over embedding generation.

Current state: the only implementation is `OllamaEmbeddingService`, selected by `reconguard.ai.provider=ollama`. The model is `nomic-embed-text`, which produces 768-dimensional vectors, matching the `vector(768)` columns.

The OpenAI implementation was removed. Setting `reconguard.ai.provider=openai` matches no bean, so no `EmbeddingService` is available. Switching providers would need a code change and a migration if the dimension changes (1536 for OpenAI). Making the provider interchangeable is planned.

### `VectorSimilaritySearchService`
This service performs the vector lookup.

It sends the embedding to the repository layer and searches the `known_issues` table using pgvector cosine similarity.

The repository returns the top-K matches, ordered by similarity.

### Orchestration
There is no single `DiagnosticWorkerService` class. The flow is wired inside `TransactionFailureConsumer`, which calls the context builder, embedding service, similarity search, and suggestion builder in order.

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
- the leg is marked failed and published as a failure event
- the AI worker builds a descriptive context
- embeddings are created from that context
- the system searches for related known issues
- the best match is used to classify or suggest a resolution

That is the complete flow without getting lost in implementation detail.

## Operational Pieces
The AI module includes some operational support:

- Micrometer metrics for classification outcomes (`AiDiagnosticMetrics`)
- actuator health endpoint
- repository-backed retrieval of known issues and suggestions
- an embedding dimension check at startup (`EmbeddingDimensionCheck`)

Planned: a Prometheus scrape endpoint. `/actuator/prometheus` is configured in the yml but is not served, because `micrometer-registry-prometheus` is not in the build yet.

## What To Mention In An Interview
Strong talking points include:

- AI is used only after a failure is detected
- embeddings and vector search make the suggestion explainable
- the worker is intentionally narrow in scope
- the current embedding provider is Ollama (local); switching to a cloud provider is planned, not built
- the approach keeps the system practical and portfolio-ready

## Short Summary
The AI integration is a downstream diagnostic pipeline. It consumes failure events, turns them into structured context, compares them with known issues using embeddings and pgvector, and saves a classified suggestion without changing the core reconciliation logic.
