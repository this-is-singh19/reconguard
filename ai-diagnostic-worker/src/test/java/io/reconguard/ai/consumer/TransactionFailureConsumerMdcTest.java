package io.reconguard.ai.consumer;

import io.reconguard.ai.builder.FailureContextBuilder;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.metrics.AiDiagnosticMetrics;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.service.ResolutionSuggestionBuilder;
import io.reconguard.ai.service.VectorSimilaritySearchService;
import io.reconguard.common.event.TransactionFailureEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionFailureConsumerMdcTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final VectorSimilaritySearchService searchService = mock(VectorSimilaritySearchService.class);
    private final ResolutionSuggestionBuilder suggestionBuilder = mock(ResolutionSuggestionBuilder.class);
    private final ResolutionSuggestionRepository repository = mock(ResolutionSuggestionRepository.class);
    private final AiDiagnosticMetrics metrics = mock(AiDiagnosticMetrics.class);

    private final TransactionFailureConsumer consumer = new TransactionFailureConsumer(
            new FailureContextBuilder(), embeddingService, searchService,
            suggestionBuilder, repository, metrics);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static TransactionFailureEvent event() {
        return new TransactionFailureEvent(
                "tx-42", "tenant-z", "order-99", new BigDecimal("5.0000"), "EUR", "FAILED",
                "missing pair", Instant.now());
    }

    @Test
    void mdcHoldsEventIdsDuringProcessingAndIsClearedAfter() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        when(embeddingService.embed(anyString())).thenAnswer(inv -> {
            seen.set(MDC.getCopyOfContextMap());
            return new float[]{0.1f};
        });
        when(searchService.searchSimilarIssues(any(), anyInt(), anyString())).thenReturn(List.of());
        when(suggestionBuilder.buildSuggestion(any(), any())).thenReturn(mock(ResolutionSuggestion.class));

        consumer.consumeFailureEvent(event());

        assertThat(seen.get())
                .containsEntry("tenantId", "tenant-z")
                .containsEntry("orderId", "order-99")
                .containsEntry("transactionId", "tx-42");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void metricCategoryIsKnownIssueCategoryNotIssueId() {
        ResolutionSuggestion matched = mock(ResolutionSuggestion.class);
        when(matched.getMatchedIssueId()).thenReturn("ISSUE-001");
        io.reconguard.ai.dto.VectorMatchProjection top = mock(io.reconguard.ai.dto.VectorMatchProjection.class);
        when(top.getCategory()).thenReturn("SETTLEMENT_LAG");

        assertThat(TransactionFailureConsumer.matchedCategory(matched, List.of(top)))
                .isEqualTo("SETTLEMENT_LAG");

        ResolutionSuggestion unmatched = mock(ResolutionSuggestion.class);
        when(unmatched.getMatchedIssueId()).thenReturn(null);
        assertThat(TransactionFailureConsumer.matchedCategory(unmatched, List.of(top))).isEqualTo("NONE");
        assertThat(TransactionFailureConsumer.matchedCategory(unmatched, List.of())).isEqualTo("NONE");
    }

    @Test
    void mdcIsClearedWhenListenerFails() {
        when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("model down"));

        assertThatThrownBy(() -> consumer.consumeFailureEvent(event()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("transactionId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }
}
