 package io.reconguard.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.reconguard.ai.domain.ResolutionSuggestion;
import io.reconguard.ai.repository.ResolutionSuggestionRepository;
import io.reconguard.common.event.TransactionFailureEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DiagnosticFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("recon_db")
                    .withUsername("recon_user")
                    .withPassword("recon_pass");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(
                    DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
            );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // The schema (known_issues, resolution_suggestions, transactions) is owned by the engine's
        // Flyway migrations. Run those against the test Postgres so ddl-auto=validate passes.
        // Gradle's test working dir is this module, hence the relative path.
        // Test producer must JSON-serialize TransactionFailureEvent (engine's producer does the same)
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");

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
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should ingest failure event, run vector diagnostic, and persist resolution suggestion")
    void shouldProcessFailureEventAndPersistResolutionSuggestion() {

        String tenantId = "tenant-space-99";
        String transactionId = "tx-integration-test-001";

        // In production the engine writes the transaction row (status FAILED) before publishing the
        // failure, and resolution_suggestions has an FK to it. Seed the same state here.
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING",
                tenantId, "Integration Test Tenant");
        jdbcTemplate.update("""
                INSERT INTO transactions (transaction_id, tenant_id, order_id, amount, currency, type, status, event_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                transactionId, tenantId, "ord-int-test-900", new BigDecimal("149.99"), "EUR",
                "PAYMENT", "FAILED", java.sql.Timestamp.from(Instant.now()));

        TransactionFailureEvent failureEvent = new TransactionFailureEvent(
                transactionId,
                tenantId,
                "ord-int-test-900",
                new BigDecimal("149.99"),
                "EUR",
                "FAILED",
                "Unmatched window expiration",
                Instant.now()
        );

        kafkaTemplate.send(
                "reconguard.transactions.failures",
                tenantId + ":ord-int-test-900",
                failureEvent
        );

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(
                        suggestionRepository.findByTenantIdAndTransactionId(
                                tenantId,
                                transactionId
                        )
                ).isNotEmpty());

        List<ResolutionSuggestion> suggestions =
                suggestionRepository.findByTenantIdAndTransactionId(
                        tenantId,
                        transactionId
                );

        ResolutionSuggestion suggestion = suggestions.get(0);

        assertThat(suggestion.getTenantId()).isEqualTo(tenantId);
        assertThat(suggestion.getTransactionId()).isEqualTo(transactionId);
        assertThat(suggestion.getConfidenceScore())
                .isNotNull()
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(suggestion.getStatus())
                .isIn("AUTO_RESOLVED", "AUTO_CLASSIFIED", "NEEDS_REVIEW");
        assertThat(suggestion.getReasoning()).isNotBlank();
        assertThat(suggestion.getSuggestedAction()).isNotBlank();

        assertThat(
                meterRegistry
                        .find("reconguard.ai.failures.processed.total")
                        .tag("tenant_id", tenantId)
                        .counter()
                        .count()
        ).isGreaterThan(0);
    }
}
