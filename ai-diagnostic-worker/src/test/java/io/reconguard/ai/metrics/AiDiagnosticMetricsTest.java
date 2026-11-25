package io.reconguard.ai.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiDiagnosticMetricsTest {

    @Test
    void suggestionOutcomeUsesCategoryTagAndTenantTagName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiDiagnosticMetrics metrics = new AiDiagnosticMetrics(registry);

        metrics.incrementSuggestionOutcome("tenant-a", "AUTO_RESOLVED", "DUPLICATE_PAYMENT");

        assertThat(registry.find(AiDiagnosticMetrics.METRIC_SUGGESTION_OUTCOME)
                .tag("tenant", "tenant-a")
                .tag("category", "DUPLICATE_PAYMENT")
                .tag("outcome_status", "AUTO_RESOLVED")
                .counter()).isNotNull();
        assertThat(registry.find(AiDiagnosticMetrics.METRIC_SUGGESTION_OUTCOME)
                .tag("tenant_id", "tenant-a")
                .counter()).isNull();
    }

    @Test
    void nullCategoryFallsBackToNone() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiDiagnosticMetrics metrics = new AiDiagnosticMetrics(registry);

        metrics.incrementSuggestionOutcome("tenant-a", "NEEDS_REVIEW", null);

        assertThat(registry.find(AiDiagnosticMetrics.METRIC_SUGGESTION_OUTCOME)
                .tag("category", "NONE")
                .counter()).isNotNull();
    }
}
