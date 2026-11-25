package io.reconguard.ai.consumer;

import io.reconguard.ai.builder.FailureContextBuilder;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.metrics.AiDiagnosticMetrics;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.service.ResolutionSuggestionBuilder;
import io.reconguard.ai.service.VectorSimilaritySearchService;
import io.reconguard.common.event.TransactionFailureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
public class TransactionFailureConsumer {

    private final FailureContextBuilder failureContextBuilder;
    private final EmbeddingService embeddingService;
    private final VectorSimilaritySearchService vectorSearchService;
    private final ResolutionSuggestionBuilder suggestionBuilder;
    private final ResolutionSuggestionRepository suggestionRepository;
    private final AiDiagnosticMetrics metrics;

    private static final int TOP_K_MATCHES = 3;

    public TransactionFailureConsumer(
            FailureContextBuilder failureContextBuilder,
            EmbeddingService embeddingService,
            VectorSimilaritySearchService vectorSearchService,
            ResolutionSuggestionBuilder suggestionBuilder,
            ResolutionSuggestionRepository suggestionRepository,
            AiDiagnosticMetrics metrics) {
        this.failureContextBuilder = failureContextBuilder;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.suggestionBuilder = suggestionBuilder;
        this.suggestionRepository = suggestionRepository;
        this.metrics = metrics;
    }

    /**
     * Listens to failed transaction events from Kafka, builds context prompts,
     * generates vector embeddings, queries known_issues via pgvector, and persists
     * the AI diagnostic resolution suggestion.
     */
    @Transactional
    @KafkaListener(
            topics = "${reconguard.topics.failed-transactions:reconguard.transactions.failures}",
            groupId = "${reconguard.ai.failure-consumer.group-id:ai-diagnostic-worker-group}",
            containerFactory = "failureKafkaListenerContainerFactory"
    )
    public void consumeFailureEvent(TransactionFailureEvent event) {
        log.info("Received failure event for processing: TxID={}, Tenant={}",
                event.transactionId(), event.tenantId());

        try {
            // 1. Build structured failure context prompt
            String failureContext = failureContextBuilder.buildFailureContext(event);
            log.debug("Built failure context prompt:\n{}", failureContext);

            // 2. Generate vector embedding with timing metric
            long embedStart = System.nanoTime();
            float[] embedding = embeddingService.embed(failureContext);
            metrics.recordEmbeddingLatency(System.nanoTime() - embedStart, "text-embedding-3-small");

            // 3. Perform pgvector HNSW similarity search
            List<VectorMatchProjection> topMatches = vectorSearchService.searchSimilarIssues(
                    embedding, TOP_K_MATCHES, event.tenantId());

            // 4. Apply multi-tier threshold classification
            ResolutionSuggestion suggestion = suggestionBuilder.buildSuggestion(event, topMatches);

            // 5. Persist diagnosis to PostgreSQL
            suggestionRepository.save(suggestion);

            // 6. Record success and outcome metrics
            metrics.incrementFailuresProcessed(event.tenantId(), "success");
            metrics.incrementSuggestionOutcome(
                    event.tenantId(),
                    suggestion.getStatus(),
                    suggestion.getMatchedIssueId());

            log.info("Successfully persisted AI diagnosis suggestion ID={} for TxID={} with Status={}",
                    suggestion.getSuggestionId(), event.transactionId(), suggestion.getStatus());

        } catch (Exception ex) {
            metrics.incrementFailuresProcessed(event.tenantId(), "error");
            log.error("Failed to process AI diagnosis for transaction ID: {}", event.transactionId(), ex);
            // Allow Kafka retry / DLQ container factory configuration to handle transient errors
            throw ex;
        }
    }
}