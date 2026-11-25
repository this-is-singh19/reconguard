package io.reconguard.ai.service;

import io.reconguard.ai.builder.FailureContextBuilder;
import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.repository.VectorSearchRepository;
import io.reconguard.common.event.TransactionFailureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticWorkerService {

    private final EmbeddingService embeddingService;
    private final VectorSearchRepository vectorSearchRepository;
    private final FailureContextBuilder failureContextBuilder;

    /**
     * Processes an incoming transaction failure, constructs contextual text,
     * generates a vector embedding, and performs vector similarity diagnostics.
     */
    public List<VectorMatchProjection> diagnoseFailure(TransactionFailureEvent event, int topK) {
        log.info("Starting AI diagnostic classification for TxId: {} [Tenant: {}]",
                event.transactionId(), event.tenantId());

        // 1. Build failure context string using FailureContextBuilder
        String contextText = failureContextBuilder.buildFailureContext(event);

        // 2. Generate vector embedding using configured provider (OpenAI / Ollama)
        float[] embedding = embeddingService.embed(contextText);

        // 3. Search vector database for top matching failure patterns
        List<VectorMatchProjection> matches = vectorSearchRepository.findTopKSimilarIssues(embedding, topK);

        if (matches.isEmpty()) {
            log.warn("No matching failure patterns found for TxId: {}", event.transactionId());
        } else {
            VectorMatchProjection topMatch = matches.get(0);
            log.info("Diagnosed TxId: {} as Pattern: '{}' (Similarity Score: {})",
                    event.transactionId(), topMatch.getPatternName(), topMatch.getSimilarity());
        }

        return matches;
    }
}