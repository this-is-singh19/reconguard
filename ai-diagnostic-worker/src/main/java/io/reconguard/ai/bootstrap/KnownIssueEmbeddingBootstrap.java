package io.reconguard.ai.bootstrap;

import io.reconguard.ai.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeds every known_issues row whose embedding is NULL using the real EmbeddingService (A1.5).
 * Rows that cannot be embedded (e.g. model unreachable) stay NULL and are retried on the next startup;
 * similarity search ignores NULL embeddings.
 */
@Slf4j
@Component
@Order(2)
public class KnownIssueEmbeddingBootstrap implements ApplicationRunner {

    private static final int BATCH_SIZE = 16;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final EmbeddingService embeddingService;

    public KnownIssueEmbeddingBootstrap(JdbcTemplate jdbc, DataSource dataSource, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.embeddingService = embeddingService;
    }

    /** Text that represents an issue for embedding: pattern name followed by its description. */
    public static String embeddingText(String patternName, String description) {
        return patternName + ". " + description;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!PgVectorSchema.isPostgres(dataSource)) {
            log.info("Known-issue embedding bootstrap skipped: database is not PostgreSQL");
            return;
        }

        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT issue_id, pattern_name, description FROM known_issues WHERE embedding IS NULL ORDER BY issue_id");
        if (pending.isEmpty()) {
            log.info("Known-issue embedding bootstrap: all known_issues already embedded");
            return;
        }

        int dimension = PgVectorSchema.embeddingColumnDimension(jdbc);
        int done = 0;
        for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
            List<Map<String, Object>> batch = pending.subList(from, Math.min(from + BATCH_SIZE, pending.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (Map<String, Object> row : batch) {
                texts.add(embeddingText((String) row.get("pattern_name"), (String) row.get("description")));
            }

            List<float[]> vectors;
            try {
                vectors = embeddingService.embedAll(texts);
            } catch (RuntimeException e) {
                log.warn("Known-issue embedding bootstrap stopped after {} of {} rows: {}. Remaining rows stay NULL until next startup.",
                        done, pending.size(), e.getMessage());
                return;
            }

            for (int i = 0; i < batch.size(); i++) {
                float[] vector = vectors.get(i);
                if (vector.length != dimension) {
                    throw new IllegalStateException(String.format(
                            "Embedding model returned %d dimensions but known_issues.embedding is vector(%d)",
                            vector.length, dimension));
                }
                jdbc.update("UPDATE known_issues SET embedding = CAST(? AS vector) WHERE issue_id = ? AND embedding IS NULL",
                        PgVectorSchema.toVectorLiteral(vector), batch.get(i).get("issue_id"));
                done++;
            }
        }
        log.info("Known-issue embedding bootstrap embedded {} rows", done);
    }
}
