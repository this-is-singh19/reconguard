package io.reconguard.ai.consumer;

import io.reconguard.ai.builder.FailureContextBuilder;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.metrics.AiDiagnosticMetrics;
import io.reconguard.ai.repository.FailureRecordRepository;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.service.ResolutionSuggestionBuilder;
import io.reconguard.ai.service.VectorSimilaritySearchService;
import io.reconguard.common.event.TransactionFailureEvent;
import io.reconguard.common.logging.TransactionMdc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TransactionFailureConsumer {

    private final FailureContextBuilder failureContextBuilder;
    private final EmbeddingService embeddingService;
    private final VectorSimilaritySearchService vectorSearchService;
    private final ResolutionSuggestionBuilder suggestionBuilder;
    private final ResolutionSuggestionRepository suggestionRepository;
    private final AiDiagnosticMetrics metrics;
    private final FailureRecordRepository failureRecords;

    private static final int TOP_K_MATCHES = 3;

    public TransactionFailureConsumer(
            FailureContextBuilder failureContextBuilder,
            EmbeddingService embeddingService,
            VectorSimilaritySearchService vectorSearchService,
            ResolutionSuggestionBuilder suggestionBuilder,
            ResolutionSuggestionRepository suggestionRepository,
            AiDiagnosticMetrics metrics,
            FailureRecordRepository failureRecords) {
        this.failureRecords = failureRecords;
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
        try (TransactionMdc ignored = TransactionMdc.open(
                event.tenantId(), event.orderId(), event.transactionId())) {
            processFailure(event);
        }
    }

    /** KnownIssue category of the matched issue (low cardinality metric tag), or "NONE" when unmatched. */
    static String matchedCategory(ResolutionSuggestion suggestion, List<VectorMatchProjection> topMatches) {
        if (suggestion.getMatchedIssueId() == null || topMatches == null || topMatches.isEmpty()) {
            return "NONE";
        }
        VectorMatchProjection top = topMatches.get(0);
        String category = top.getCategory();
        return category != null && !category.isBlank() ? category : "NONE";
    }

    private void processFailure(TransactionFailureEvent event) {
        log.info("Received failure event for processing: TxID={}, Tenant={}",
                event.transactionId(), event.tenantId());

        try {
            // 1. Build structured failure context prompt
            String failureContext = failureContextBuilder.buildFailureContext(event);
            log.debug("Built failure context prompt:\n{}", failureContext);

            // 2. Generate vector embedding with timing metric
            long embedStart = System.nanoTime();
            float[] embedding = embeddingService.embed(failureContext);
            metrics.recordEmbeddingLatency(System.nanoTime() - embedStart, embeddingService.modelName());

            // Store the context embedding on the engine-written failure row; keep its id for linking
            Optional<UUID> failureId = failureRecords.attachEmbedding(
                    event.tenantId(), event.transactionId(), embedding);

            // 3. Perform pgvector HNSW similarity search
            List<VectorMatchProjection> topMatches = vectorSearchService.searchSimilarIssues(
                    embedding, TOP_K_MATCHES, event.tenantId());

            // 4. Apply multi-tier threshold classification
            ResolutionSuggestion suggestion = suggestionBuilder.buildSuggestion(event, topMatches);

            failureId.ifPresent(suggestion::setFailureId);

            // 5. Persist diagnosis to PostgreSQL (deterministic id => redelivery upserts, A2.9)
            suggestionRepository.save(suggestion);

            // AUTO_RESOLVED: flag the failure resolved. No transaction status change or money movement (G4.4).
            if (suggestion.isAutoApplied()) {
                failureRecords.markResolved(event.tenantId(), event.transactionId());
            }

            // 6. Record success and outcome metrics
            metrics.incrementFailuresProcessed(event.tenantId(), "success");
            metrics.incrementSuggestionOutcome(
                    event.tenantId(),
                    suggestion.getStatus(),
                    matchedCategory(suggestion, topMatches));

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
