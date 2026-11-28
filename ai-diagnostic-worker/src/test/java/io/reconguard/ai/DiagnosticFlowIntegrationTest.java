package io.reconguard.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.common.event.TransactionFailureEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5: failure event on Kafka -> embedding -> pgvector search -> suggestion persisted, against real Postgres
 * (engine Flyway schema) and Kafka. The embedding model is stubbed with deterministic vectors, so the
 * similarity score of each scenario is known in advance and the expected status is asserted exactly.
 */
@SpringBootTest
@Testcontainers
@Import(DiagnosticFlowIntegrationTest.ControlledEmbeddingConfig.class)
class DiagnosticFlowIntegrationTest {

    private static final int DIM = 768;
    private static final String TENANT = "tenant-ai-it";
    private static final String FAILURES_TOPIC = "reconguard.transactions.failures";
    /** Failure vectors mix e0 with this reserved axis; known-issue vectors never use it. */
    private static final int RESERVED_AXIS = DIM - 1;

    /**
     * Known-issue texts embed to a one-hot axis in 1..766 (orthogonal to e0 and to the reserved axis).
     * A failure whose reason contains SCENARIO_<n> embeds to cos(angle) = n/100 against e0, so the issue
     * pinned to e0 is the top match with exactly that similarity.
     */
    @TestConfiguration
    static class ControlledEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingService controlledEmbeddingService() {
            return new EmbeddingService() {
                @Override
                public float[] embed(String text) {
                    float[] v = new float[DIM];
                    int at = text.indexOf("SCENARIO_");
                    if (at >= 0) {
                        double cos = Integer.parseInt(text.substring(at + 9, at + 11)) / 100.0;
                        v[0] = (float) cos;
                        v[RESERVED_AXIS] = (float) Math.sqrt(1 - cos * cos);
                    } else {
                        v[1 + Math.floorMod(text.hashCode(), RESERVED_AXIS - 1)] = 1f;
                    }
                    return v;
                }

                @Override
                public List<float[]> embedAll(List<String> texts) {
                    return texts.stream().map(this::embed).toList();
                }
            };
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("recon_db").withUsername("recon_user").withPassword("recon_pass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // The test producer JSON-serializes TransactionFailureEvent, as the engine's producer does
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");
        // The schema is owned by the engine's Flyway migrations (this module's working dir is the module root)
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
                () -> "filesystem:../reconciliation-engine/src/main/resources/db/migration");
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private ResolutionSuggestionRepository suggestionRepository;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private JdbcTemplate jdbc;

    private String pinnedIssueId;

    /** Pins one known issue to e0 so scenario failures match it with a known cosine similarity. */
    @BeforeEach
    void pinKnownIssueToE0() {
        pinnedIssueId = jdbc.queryForObject("SELECT issue_id FROM known_issues ORDER BY issue_id LIMIT 1", String.class);
        StringBuilder vec = new StringBuilder("[1");
        for (int i = 1; i < DIM; i++) {
            vec.append(",0");
        }
        vec.append("]");
        jdbc.update("UPDATE known_issues SET embedding = CAST(? AS vector) WHERE issue_id = ?", vec.toString(), pinnedIssueId);
    }

    /** Engine writes the FAILED transaction row before publishing; resolution_suggestions has an FK to it. */
    private String seedFailedTransaction() {
        String tx = "tx-" + UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (tenant_id, name) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING", TENANT, TENANT);
        jdbc.update("""
                INSERT INTO transactions (transaction_id, tenant_id, order_id, amount, currency, type, status, event_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tx, TENANT, "ord-" + tx, new BigDecimal("149.99"), "EUR", "PAYMENT", "FAILED",
                java.sql.Timestamp.from(Instant.now()));
        return tx;
    }

    private void publishFailure(String tx, String reason) {
        publishFailure(tx, reason, tx);
    }

    /** keyTx selects the Kafka key (partition); events sharing a key are consumed in publish order. */
    private void publishFailure(String tx, String reason, String keyTx) {
        kafkaTemplate.send(FAILURES_TOPIC, TENANT + ":ord-" + keyTx, new TransactionFailureEvent(
                tx, TENANT, "ord-" + tx, new BigDecimal("149.99"), "EUR", "FAILED", reason, Instant.now()));
    }

    private ResolutionSuggestion awaitSuggestion(String tx) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(suggestionRepository.findByTenantIdAndTransactionId(TENANT, tx)).isNotEmpty());
        return suggestionRepository.findByTenantIdAndTransactionId(TENANT, tx).get(0);
    }

    @Test
    @DisplayName("Similarity 0.95 -> AUTO_RESOLVED, auto-applied, linked to the matched known issue")
    void highConfidenceAutoResolves() {
        String tx = seedFailedTransaction();
        publishFailure(tx, "SCENARIO_95 unmatched window");

        ResolutionSuggestion s = awaitSuggestion(tx);

        assertThat(s.getStatus()).isEqualTo("AUTO_RESOLVED");
        assertThat(s.isAutoApplied()).isTrue();
        assertThat(s.getMatchedIssueId()).isEqualTo(pinnedIssueId);
        assertThat(s.getConfidenceScore()).isBetween(new BigDecimal("0.9400"), new BigDecimal("0.9600"));
        assertThat(s.getTenantId()).isEqualTo(TENANT);
        assertThat(s.getReasoning()).isNotBlank();
        assertThat(s.getSuggestedAction()).isNotBlank();
    }

    @Test
    @DisplayName("Similarity 0.80 -> AUTO_CLASSIFIED, not auto-applied")
    void mediumConfidenceAutoClassifies() {
        String tx = seedFailedTransaction();
        publishFailure(tx, "SCENARIO_80 unmatched window");

        ResolutionSuggestion s = awaitSuggestion(tx);

        assertThat(s.getStatus()).isEqualTo("AUTO_CLASSIFIED");
        assertThat(s.isAutoApplied()).isFalse();
        assertThat(s.getMatchedIssueId()).isEqualTo(pinnedIssueId);
        assertThat(s.getSuggestedAction()).startsWith("REVIEW_RECOMMENDED");
    }

    @Test
    @DisplayName("Similarity 0.10 -> NEEDS_REVIEW, escalated to an operator")
    void lowConfidenceNeedsReview() {
        String tx = seedFailedTransaction();
        publishFailure(tx, "SCENARIO_10 unmatched window");

        ResolutionSuggestion s = awaitSuggestion(tx);

        assertThat(s.getStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(s.isAutoApplied()).isFalse();
        assertThat(s.getSuggestedAction()).startsWith("ESCALATE_TO_OPERATOR");
    }

    @Test
    @DisplayName("Redelivering the same failure keeps exactly one suggestion (deterministic id, idempotent upsert)")
    void redeliveryDoesNotDuplicate() {
        String tx = seedFailedTransaction();
        publishFailure(tx, "SCENARIO_95 first delivery");
        awaitSuggestion(tx);

        publishFailure(tx, "SCENARIO_95 redelivery");
        // A sentinel on the same key is processed after the redelivery (same partition, in order)
        String sentinel = seedFailedTransaction();
        publishFailure(sentinel, "SCENARIO_10 sentinel", tx);
        awaitSuggestion(sentinel);

        assertThat(suggestionRepository.findByTenantIdAndTransactionId(TENANT, tx)).hasSize(1);
        assertThat(meterRegistry.find("reconguard.ai.failures.processed.total").tag("tenant", TENANT).counter().count())
                .isGreaterThan(0);
    }
}
