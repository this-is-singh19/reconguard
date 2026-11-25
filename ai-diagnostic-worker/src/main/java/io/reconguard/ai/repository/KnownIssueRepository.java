package io.reconguard.ai.repository;

import io.reconguard.ai.domain.KnownIssue;
import io.reconguard.ai.dto.VectorMatchProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnownIssueRepository extends JpaRepository<KnownIssue, String> {

    @Query(value = """
            SELECT 
                k.issue_id AS issueId,
                k.pattern_name AS patternName,
                k.category AS category,
                k.description AS description,
                k.recommended_action AS recommendedAction,
                (1 - (k.embedding <=> CAST(:vector AS vector))) AS similarity
            FROM known_issues k
            WHERE k.embedding IS NOT NULL
            ORDER BY k.embedding <=> CAST(:vector AS vector) ASC
            LIMIT :topK
            """, nativeQuery = true)
    List<VectorMatchProjection> findTopKSimilarIssues(
            @Param("vector") String vectorString,
            @Param("topK") int topK
    );

        @Query(value = """
        SELECT id, tenant_id, category, pattern, resolution_template, created_at 
        FROM known_issues
        WHERE tenant_id = :tenantId
        ORDER BY embedding <-> CAST(:queryVector AS vector)
        LIMIT :limit
        """, nativeQuery = true)
        List<KnownIssue> findSimilarIssues(
                @Param("tenantId") String tenantId,
                @Param("queryVector") String queryVector,
                @Param("limit") int limit
        );

}