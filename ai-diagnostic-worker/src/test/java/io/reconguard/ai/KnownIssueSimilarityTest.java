package io.reconguard.ai;

import io.reconguard.ai.bootstrap.KnownIssueEmbeddingBootstrap;
import io.reconguard.ai.dto.VectorMatchProjection;
import io.reconguard.ai.service.EmbeddingService;
import io.reconguard.ai.service.VectorSimilaritySearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real migrations + real startup bootstrap, with a deterministic bag-of-words embedding in place of Ollama (0F.6).
 * Proves the seeded issues are no longer identical under similarity search.
 */
@SpringBootTest(properties = {
        "reconguard.ai.provider=fake",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
class KnownIssueSimilarityTest {

    private static final int DIM = 768;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("recon_similarity_db")
                    .withUsername("recon_user")
                    .withPassword("recon_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
                () -> "filesystem:../reconciliation-engine/src/main/resources/db/migration");
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        EmbeddingService embeddingService() {
            return new BagOfWordsEmbeddingService();
        }
    }

    /** Each distinct word lands in a hashed dimension, so different texts get different vectors. */
    static final class BagOfWordsEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            float[] vector = new float[DIM];
            for (String word : text.toLowerCase().split("\\W+")) {
                if (!word.isEmpty()) {
                    vector[Math.floorMod(word.hashCode(), DIM)] += 1f;
                }
            }
            return vector;
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }

    @Autowired
    private VectorSimilaritySearchService similarityService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Startup bootstrap embeds every seeded known_issue")
    void bootstrapEmbedsAllSeededIssues() {
        Integer missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM known_issues WHERE embedding IS NULL", Integer.class);
        assertThat(missing).isZero();
    }

    @Test
    @DisplayName("Different issues produce different similarity scores; the matching issue ranks first")
    void differentIssuesHaveDifferentSimilarity() {
        Map<String, Object> issue1 = jdbc.queryForMap(
                "SELECT pattern_name, description FROM known_issues WHERE issue_id = 'ISSUE-001'");
        float[] query = embeddingService.embed(KnownIssueEmbeddingBootstrap.embeddingText(
                (String) issue1.get("pattern_name"), (String) issue1.get("description")));

        List<VectorMatchProjection> matches = similarityService.searchSimilarIssues(query, 50, "tenant-test");

        assertThat(matches).hasSize(50);
        assertThat(matches.get(0).getIssueId()).isEqualTo("ISSUE-001");
        assertThat(matches.get(0).getSimilarity()).isGreaterThan(0.999);
        // The old constant placeholders gave every issue the same score; a different issue must score lower.
        assertThat(matches.get(1).getSimilarity()).isLessThan(0.999);
        long distinctScores = matches.stream().map(VectorMatchProjection::getSimilarity).distinct().count();
        assertThat(distinctScores).as("similarity must differ between issues").isGreaterThan(1);
    }

    @Test
    @DisplayName("Similarity search ignores issues whose embedding is NULL")
    void similaritySearchExcludesNullEmbeddings() {
        String issueId = "ISSUE-002";
        float[] original = jdbc.query(
                "SELECT embedding::text AS e FROM known_issues WHERE issue_id = ?",
                rs -> {
                    rs.next();
                    return parseVector(rs.getString("e"));
                }, issueId);
        try {
            jdbc.update("UPDATE known_issues SET embedding = NULL WHERE issue_id = ?", issueId);
            float[] query = embeddingService.embed("Gateway Ingestion Timeout");
            List<VectorMatchProjection> matches = similarityService.searchSimilarIssues(query, 50, "tenant-test");
            assertThat(matches).noneMatch(m -> issueId.equals(m.getIssueId()));
        } finally {
            jdbc.update("UPDATE known_issues SET embedding = CAST(? AS vector) WHERE issue_id = ?",
                    Arrays.toString(original), issueId);
        }
    }

    private static float[] parseVector(String text) {
        String[] parts = text.replace("[", "").replace("]", "").split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
