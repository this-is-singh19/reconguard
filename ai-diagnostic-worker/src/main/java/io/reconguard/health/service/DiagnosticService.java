package io.reconguard.health.service;

import io.reconguard.ai.domain.KnownIssue;
import io.reconguard.ai.repository.KnownIssueRepository;
import io.reconguard.ai.service.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class DiagnosticService {

    private final EmbeddingService embeddingService;
    private final KnownIssueRepository knownIssueRepository;

    public DiagnosticService(EmbeddingService embeddingService, KnownIssueRepository knownIssueRepository) {
        this.embeddingService = embeddingService;
        this.knownIssueRepository = knownIssueRepository;
    }

    public List<KnownIssue> findDiagnoses(String tenantId, String errorDescription, int limit) {
        float[] vector = embeddingService.embed(errorDescription);
        String vectorString = Arrays.toString(vector);
        return knownIssueRepository.findSimilarIssues(tenantId, vectorString, limit);
    }
}