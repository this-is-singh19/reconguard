package io.reconguard.ai.service;

import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.common.event.TransactionFailureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class ResolutionSuggestionBuilder {

    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.90;
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.75;

    /**
     * Maps the top vector match against threshold boundaries and constructs
     * a ResolutionSuggestion entity ready for persistence.
     */
    public ResolutionSuggestion buildSuggestion(TransactionFailureEvent event, List<VectorMatchProjection> matches) {
        String suggestionId = "sug-" + UUID.randomUUID();
        Instant now = Instant.now();

        if (matches == null || matches.isEmpty()) {
            log.info("No vector matches found for TxID: {}. Defaulting to NEEDS_REVIEW.", event.transactionId());
            return buildFallbackSuggestion(suggestionId, event, "No vector similarity match found in known_issues library.", now);
        }

        VectorMatchProjection topMatch = matches.get(0);
        double similarityScore = topMatch.getSimilarity() != null ? topMatch.getSimilarity() : 0.0;
        BigDecimal formattedScore = BigDecimal.valueOf(similarityScore).setScale(4, RoundingMode.HALF_UP);

        String status;
        String reasoning;
        String suggestedAction;

        if (similarityScore >= HIGH_CONFIDENCE_THRESHOLD) {
            status = "AUTO_RESOLVED";
            reasoning = String.format("High confidence vector match (Score: %.4f) against pattern '%s' [%s]. %s",
                    similarityScore, topMatch.getPatternName(), topMatch.getIssueId(), topMatch.getDescription());
            suggestedAction = topMatch.getRecommendedAction();

        } else if (similarityScore >= MEDIUM_CONFIDENCE_THRESHOLD) {
            status = "AUTO_CLASSIFIED";
            reasoning = String.format("Moderate confidence vector match (Score: %.4f) against pattern '%s' [%s]. Manual confirmation advised.",
                    similarityScore, topMatch.getPatternName(), topMatch.getIssueId());
            suggestedAction = "REVIEW_RECOMMENDED: " + topMatch.getRecommendedAction();

        } else {
            status = "NEEDS_REVIEW";
            reasoning = String.format("Low confidence match (Score: %.4f) below minimum 0.75 threshold. Highest similarity was '%s'.",
                    similarityScore, topMatch.getPatternName());
            suggestedAction = "ESCALATE_TO_OPERATOR: Inspect missing pair event or gateway timeout parameters.";
        }

        ResolutionSuggestion suggestion = new ResolutionSuggestion();
        suggestion.setSuggestionId(suggestionId);
        suggestion.setTransactionId(event.transactionId());
        suggestion.setTenantId(event.tenantId());
        suggestion.setMatchedIssueId(topMatch.getIssueId());
        suggestion.setConfidenceScore(formattedScore);
        suggestion.setStatus(status);
        suggestion.setReasoning(reasoning);
        suggestion.setSuggestedAction(suggestedAction);
        suggestion.setCreatedAt(now);

        log.info("Built resolution suggestion for TxID: {}, Status: {}, Score: {}",
                event.transactionId(), status, formattedScore);

        return suggestion;
    }

    private ResolutionSuggestion buildFallbackSuggestion(String id, TransactionFailureEvent event, String reason, Instant now) {
        ResolutionSuggestion suggestion = new ResolutionSuggestion();
        suggestion.setSuggestionId(id);
        suggestion.setTransactionId(event.transactionId());
        suggestion.setTenantId(event.tenantId());
        suggestion.setMatchedIssueId(null);
        suggestion.setConfidenceScore(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        suggestion.setStatus("NEEDS_REVIEW");
        suggestion.setReasoning(reason);
        suggestion.setSuggestedAction("MANUAL_INVESTIGATION_REQUIRED: No corresponding failure pattern matched in knowledge base.");
        suggestion.setCreatedAt(now);
        return suggestion;
    }
}