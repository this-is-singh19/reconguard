package io.reconguard.ai.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Fails fast at startup when the configured embedding dimension does not match the
 * pgvector column width of known_issues.embedding (A1.4).
 */
@Slf4j
@Component
@Order(1)
public class EmbeddingDimensionCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final int configuredDimension;

    public EmbeddingDimensionCheck(JdbcTemplate jdbc,
                                   DataSource dataSource,
                                   @Value("${reconguard.ai.embedding.dimension:768}") int configuredDimension) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.configuredDimension = configuredDimension;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!PgVectorSchema.isPostgres(dataSource)) {
            log.info("Embedding dimension check skipped: database is not PostgreSQL");
            return;
        }
        int columnDimension = PgVectorSchema.embeddingColumnDimension(jdbc);
        if (columnDimension != configuredDimension) {
            throw new IllegalStateException(String.format(
                    "Embedding dimension mismatch: reconguard.ai.embedding.dimension=%d but known_issues.embedding is vector(%d). "
                            + "Align the embedding model with the migrations (V4/V7).", configuredDimension, columnDimension));
        }
        log.info("Embedding dimension check passed: vector({})", columnDimension);
    }
}
