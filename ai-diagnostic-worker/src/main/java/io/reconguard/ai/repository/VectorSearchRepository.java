package io.reconguard.ai.repository;

import io.reconguard.ai.domain.KnownIssue;
import io.reconguard.ai.dto.VectorMatchProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorSearchRepository {

    private final KnownIssueRepository knownIssueRepository;

    /**
     * Executes cosine distance vector search across all known issues.
     */
    public List<VectorMatchProjection> findTopKSimilarIssues(float[] embedding, int topK) {
        String vectorString = toVectorSqlString(embedding);
        return knownIssueRepository.findTopKSimilarIssues(vectorString, topK);
    }

    /**
     * Executes L2 distance vector search scoped to a specific tenant.
     */
    public List<KnownIssue> findSimilarIssuesByTenant(String tenantId, float[] embedding, int limit) {
        String vectorString = toVectorSqlString(embedding);
        return knownIssueRepository.findSimilarIssues(tenantId, vectorString, limit);
    }

    /**
     * Converts float array embedding to PostgreSQL pgvector string representation: [0.1,0.2,...]
     */
    private String toVectorSqlString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}