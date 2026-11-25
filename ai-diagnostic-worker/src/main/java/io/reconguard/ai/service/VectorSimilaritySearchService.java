package io.reconguard.ai.service;

import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.metrics.AiDiagnosticMetrics;
import io.reconguard.ai.repository.KnownIssueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class VectorSimilaritySearchService {

    private final KnownIssueRepository knownIssueRepository;
    private final AiDiagnosticMetrics metrics;

    public VectorSimilaritySearchService(KnownIssueRepository knownIssueRepository, AiDiagnosticMetrics metrics) {
        this.knownIssueRepository = knownIssueRepository;
        this.metrics = metrics;
    }

    /**
     * Executes top-K cosine similarity search against known_issues using pgvector HNSW index.
     *
     * @param embedding Float array vector returned by EmbeddingService
     * @param topK Number of nearest neighbors to retrieve
     * @param tenantId Tenant ID for metric tag tracing
     * @return List of matching projections sorted descending by similarity score
     */
    @Transactional(readOnly = true)
    public List<VectorMatchProjection> searchSimilarIssues(float[] embedding, int topK, String tenantId) {
        if (embedding == null || embedding.length == 0) {
            log.warn("Empty or null embedding passed to similarity search");
            return List.of();
        }

        long startTime = System.nanoTime();
        try {
            // Convert float array to PostgreSQL vector literal string: '[0.1, 0.2, ...]'
            String vectorString = Arrays.toString(embedding);

            log.debug("Executing pgvector top-{} similarity search", topK);
            List<VectorMatchProjection> matches = knownIssueRepository.findTopKSimilarIssues(vectorString, topK);

            log.info("Found {} vector matches in known_issues table", matches.size());
            matches.forEach(m -> log.debug("Match: IssueID={}, Category={}, Score={}",
                    m.getIssueId(), m.getCategory(), m.getSimilarity()));

            return matches;
        } finally {
            metrics.recordVectorSearchLatency(System.nanoTime() - startTime, tenantId);
        }
    }
}