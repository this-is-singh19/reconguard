package io.reconguard.reconciliation.service;

import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.repository.KnownIssueRepository;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.common.model.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FailureDiagnosticEngineService {

    private final KnownIssueRepository knownIssueRepository;
    private final ResolutionSuggestionRepository suggestionRepository;
    private final EmbeddingService embeddingService;

    public FailureDiagnosticEngineService(KnownIssueRepository knownIssueRepository,
                                          ResolutionSuggestionRepository suggestionRepository,
                                          EmbeddingService embeddingService) {
        this.knownIssueRepository = knownIssueRepository;
        this.suggestionRepository = suggestionRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * Analyzes swept failure events using vector similarity and generates AI classification records.
     */
    @Transactional
    public void diagnoseFailure(TransactionEvent event, String failureReason) {
        log.info("Analyzing failure pattern for TxId: {}, Reason: {}", event.transactionId(), failureReason);

        String contextText = String.format("Type: %s, Amount: %s %s, Reason: %s",
                event.type(),
                event.monetaryAmount().value(),
                event.monetaryAmount().currency(),
                failureReason);

        // 1. Generate float[] vector from text
        float[] vectorArray = embeddingService.embed(contextText);
        String vectorString = Arrays.toString(vectorArray);

        // 2. Query top K similar issues returning VectorMatchProjection
        List<VectorMatchProjection> topMatches = knownIssueRepository.findTopKSimilarIssues(vectorString, 1);

        VectorMatchProjection matchedIssue = topMatches.isEmpty() ? null : topMatches.get(0);

        // 3. Extract similarity score or fallback
        Double similarityScore = (matchedIssue != null) ? matchedIssue.getSimilarity() : null;
        BigDecimal confidenceScore = similarityScore != null
                ? BigDecimal.valueOf(similarityScore)
                : new BigDecimal("0.0000");

        String status = confidenceScore.compareTo(new BigDecimal("0.8000")) >= 0
                ? "AUTO_CLASSIFIED"
                : "NEEDS_REVIEW";

        String suggestedAction = matchedIssue != null
                ? matchedIssue.getRecommendedAction()
                : "FLAG_FOR_MANUAL_AUDIT";

        String reasoning = matchedIssue != null
                ? "Matched pattern: " + matchedIssue.getPatternName()
                : "No matching pattern found in knowledge base.";

        // 4. Construct ResolutionSuggestion entity matching your flat entity schema
        ResolutionSuggestion suggestion = new ResolutionSuggestion();
        suggestion.setSuggestionId("SUG-" + UUID.randomUUID().toString().substring(0, 8));
        suggestion.setTransactionId(event.transactionId());
        suggestion.setTenantId(event.tenantId());
        suggestion.setMatchedIssueId(matchedIssue != null ? matchedIssue.getIssueId() : null);
        suggestion.setConfidenceScore(confidenceScore);
        suggestion.setStatus(status);
        suggestion.setReasoning(reasoning);
        suggestion.setSuggestedAction(suggestedAction);
        suggestion.setCreatedAt(Instant.now());

        suggestionRepository.save(suggestion);
        log.info("Persisted AI resolution suggestion ID: {}, Status: {}, MatchedIssueId: {}",
                suggestion.getSuggestionId(), status, suggestion.getMatchedIssueId());
    }
}