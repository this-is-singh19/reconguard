package io.reconguard.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AiDiagnosticMetrics {

    private final MeterRegistry registry;

    // Metric names
    public static final String METRIC_FAILURES_PROCESSED = "reconguard.ai.failures.processed.total";
    public static final String METRIC_SUGGESTION_OUTCOME = "reconguard.ai.suggestions.outcome.total";
    public static final String METRIC_VECTOR_SEARCH_LATENCY = "reconguard.ai.vector.search.latency";
    public static final String METRIC_EMBEDDING_LATENCY = "reconguard.ai.embedding.generation.latency";
    public static final String METRIC_CLASSIFICATION_RATE = "recon.ai.classification_rate";

    private final ConcurrentHashMap<String, AtomicLong> totalClassifications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> successfulClassifications = new ConcurrentHashMap<>();

    public AiDiagnosticMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void incrementFailuresProcessed(String tenantId, String status) {
        Counter.builder(METRIC_FAILURES_PROCESSED)
                .description("Total number of transaction failure events processed by AI worker")
                .tag("tenant", tenantId)
                .tag("status", status) // success | error
                .register(registry)
                .increment();
    }

    public void incrementSuggestionOutcome(String tenantId, String outcomeStatus, String category) {
        Counter.builder(METRIC_SUGGESTION_OUTCOME)
                .description("Distribution of AI resolution suggestion outcomes")
                .tag("tenant", tenantId)
                .tag("outcome_status", outcomeStatus) // AUTO_RESOLVED | AUTO_CLASSIFIED | NEEDS_REVIEW
                // category must be the KnownIssue category (low cardinality), never matchedIssueId.
                .tag("category", category != null ? category : "NONE")
                .register(registry)
                .increment();

        if ("AUTO_RESOLVED".equalsIgnoreCase(outcomeStatus) || "AUTO_CLASSIFIED".equalsIgnoreCase(outcomeStatus)) {
            successfulClassifications.computeIfAbsent(tenantId, k -> new AtomicLong(0)).incrementAndGet();
        }

        totalClassifications.computeIfAbsent(tenantId, k -> {
            AtomicLong total = new AtomicLong(0);
            Gauge.builder(METRIC_CLASSIFICATION_RATE, total, t -> {
                        long tot = t.get();
                        if (tot == 0) return 0.0;
                        long success = successfulClassifications.getOrDefault(tenantId, new AtomicLong(0)).get();
                        return (double) success / tot;
                    })
                    .description("AI successful classification rate (AUTO_RESOLVED + AUTO_CLASSIFIED / Total)")
                    .tag("tenant", tenantId)
                    .register(registry);
            return total;
        }).incrementAndGet();
    }

    public void recordVectorSearchLatency(long durationNanos, String tenantId) {
        Timer.builder(METRIC_VECTOR_SEARCH_LATENCY)
                .description("Latency of pgvector HNSW similarity search queries")
                .tag("tenant", tenantId)
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordEmbeddingLatency(long durationNanos, String modelName) {
        Timer.builder(METRIC_EMBEDDING_LATENCY)
                .description("Latency of embedding generation calls")
                .tag("model", modelName)
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}