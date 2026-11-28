package io.reconguard.ai.service;

import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.common.event.TransactionFailureEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResolutionSuggestionBuilderTest {

    private final ResolutionSuggestionBuilder builder = new ResolutionSuggestionBuilder();

    private static TransactionFailureEvent event() {
        return new TransactionFailureEvent("tx-1", "tenant-a", "order-1", new BigDecimal("1.00"), "USD",
                "FAILED", "missing pair", Instant.now());
    }

    private static VectorMatchProjection match(double sim) {
        VectorMatchProjection m = mock(VectorMatchProjection.class);
        when(m.getSimilarity()).thenReturn(sim);
        when(m.getIssueId()).thenReturn("ISSUE-1");
        when(m.getPatternName()).thenReturn("p");
        when(m.getRecommendedAction()).thenReturn("do it");
        return m;
    }

    @Test
    void suggestionIdIsDeterministicPerTenantAndTransaction() {
        ResolutionSuggestion a = builder.buildSuggestion(event(), List.of(match(0.95)));
        ResolutionSuggestion b = builder.buildSuggestion(event(), List.of(match(0.95)));
        assertThat(a.getSuggestionId()).isEqualTo(b.getSuggestionId())
                .isEqualTo(ResolutionSuggestionBuilder.suggestionId("tenant-a", "tx-1"));
        assertThat(ResolutionSuggestionBuilder.suggestionId("tenant-b", "tx-1")).isNotEqualTo(a.getSuggestionId());
    }

    private String statusFor(double sim) {
        return builder.buildSuggestion(event(), List.of(match(sim))).getStatus();
    }

    @Test
    void thresholdBoundaries() {
        assertThat(statusFor(0.90)).isEqualTo("AUTO_RESOLVED");
        assertThat(statusFor(0.8999)).isEqualTo("AUTO_CLASSIFIED");
        assertThat(statusFor(0.75)).isEqualTo("AUTO_CLASSIFIED");
        assertThat(statusFor(0.7499)).isEqualTo("NEEDS_REVIEW");
        assertThat(statusFor(1.0)).isEqualTo("AUTO_RESOLVED");
        assertThat(statusFor(0.0)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void emptyOrNullMatchesFallBackToNeedsReviewWithZeroScore() {
        for (List<VectorMatchProjection> m : java.util.Arrays.asList(List.<VectorMatchProjection>of(), null)) {
            ResolutionSuggestion s = builder.buildSuggestion(event(), m);
            assertThat(s.getStatus()).isEqualTo("NEEDS_REVIEW");
            assertThat(s.getMatchedIssueId()).isNull();
            assertThat(s.getConfidenceScore()).isEqualByComparingTo("0");
        }
    }

    @Test
    void usesTopMatchOnly() {
        ResolutionSuggestion s = builder.buildSuggestion(event(), List.of(match(0.80), match(0.99)));
        assertThat(s.getStatus()).isEqualTo("AUTO_CLASSIFIED");
    }

    @Test
    void autoAppliedOnlyForAutoResolved() {
        assertThat(builder.buildSuggestion(event(), List.of(match(0.95))).isAutoApplied()).isTrue();
        assertThat(builder.buildSuggestion(event(), List.of(match(0.80))).isAutoApplied()).isFalse();
        assertThat(builder.buildSuggestion(event(), List.of(match(0.10))).isAutoApplied()).isFalse();
        assertThat(builder.buildSuggestion(event(), List.of()).isAutoApplied()).isFalse();
    }
}
