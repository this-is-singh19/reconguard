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

    @Test
    void autoAppliedOnlyForAutoResolved() {
        assertThat(builder.buildSuggestion(event(), List.of(match(0.95))).isAutoApplied()).isTrue();
        assertThat(builder.buildSuggestion(event(), List.of(match(0.80))).isAutoApplied()).isFalse();
        assertThat(builder.buildSuggestion(event(), List.of(match(0.10))).isAutoApplied()).isFalse();
        assertThat(builder.buildSuggestion(event(), List.of()).isAutoApplied()).isFalse();
    }
}
