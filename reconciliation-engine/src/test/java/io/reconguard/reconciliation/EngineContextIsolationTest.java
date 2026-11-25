package io.reconguard.reconciliation;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * health endpoint must be UP. Runs on the H2 test profile (no Postgres, no Ollama, embedded Kafka).
 */
@SpringBootTest(properties = {
        // Redis is mocked in this context and no Redis server runs in CI; do not let its health indicator decide.
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "reconguard.transactions.raw",
                "reconguard.transactions.raw.DLQ",
                "reconguard.transactions.failures"
        }
)
@ActiveProfiles("test")
@DirtiesContext
class EngineContextIsolationTest {

    private static final String FAILURES_TOPIC = "reconguard.transactions.failures";
    private static final String RAW_TOPIC = "reconguard.transactions.raw";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MockMvc mockMvc;

    // Present only to satisfy Redis-backed beans in the engine; the AI worker has no Redis dependency.
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

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
