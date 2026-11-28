package io.reconguard.reconciliation.it;

import io.reconguard.ai.bootstrap.EmbeddingDimensionCheck;
import io.reconguard.ai.bootstrap.KnownIssueEmbeddingBootstrap;
import io.reconguard.ai.consumer.TransactionFailureConsumer;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.service.ResolutionSuggestionBuilder;
import io.reconguard.reconciliation.consumer.TransactionConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards 0F.9 / 0F.30: the engine context must not contain the AI worker's beans, and the engine's own
 * health endpoint must be UP. Runs on real Postgres/Redis/Kafka containers (no Ollama).
 */
@AutoConfigureMockMvc
class EngineContextIsolationTest extends AbstractEngineIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Engine context has no AI worker beans (listeners, services, bootstrap runners, controller, health)")
    void engineContextExcludesAiWorkerBeans() {
        assertThat(context.getBeanNamesForType(TransactionFailureConsumer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmbeddingService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ResolutionSuggestionBuilder.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmbeddingDimensionCheck.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KnownIssueEmbeddingBootstrap.class)).isEmpty();
        assertThat(context.containsBean("aiWorker")).isFalse();
        assertThat(context.containsBean("failureKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("diagnosticController")).isFalse();
    }

    @Test
    @DisplayName("Engine builds no Spring AI model (would otherwise default to a model other than nomic-embed-text)")
    void engineBuildsNoSpringAiModel() throws ClassNotFoundException {
        // Spring AI is only on the runtime classpath here (transitive via :ai-diagnostic-worker), so resolve it by name.
        Class<?> embeddingModel = Class.forName("org.springframework.ai.embedding.EmbeddingModel");
        assertThat(context.getBeanProvider(embeddingModel).getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("Engine listens on the raw topic only; failures are published, never consumed by the engine")
    void engineListensOnlyOnRawTopic() {
        assertThat(context.getBeanNamesForType(TransactionConsumer.class)).hasSize(1);

        List<String> topics = listenerRegistry.getListenerContainers().stream()
                .map(MessageListenerContainer::getContainerProperties)
                .flatMap(p -> Arrays.stream(p.getTopics()))
                .toList();

        assertThat(topics).contains(RAW_TOPIC);
        assertThat(topics).doesNotContain(FAILURES_TOPIC);
    }

    @Test
    @DisplayName("Engine /actuator/health is UP")
    void engineHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
