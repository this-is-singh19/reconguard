package io.reconguard.ai;

import io.reconguard.ai.bootstrap.EmbeddingDimensionCheck;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs every reconciliation-engine Flyway migration against an empty pgvector database (0F.28),
 * then checks the embedding dimension guard against the migrated schema (A1.4).
 */
@Testcontainers
class EngineSchemaCleanDbTest {

    private static final String MIGRATIONS = "filesystem:../reconciliation-engine/src/main/resources/db/migration";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("recon_clean_db")
                    .withUsername("recon_user")
                    .withPassword("recon_pass");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrateEmptyDatabase() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .load();
        assertThat(flyway.info().pending()).as("clean DB starts with pending migrations").isNotEmpty();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).as("all engine migrations succeed on an empty DB").isTrue();
        assertThat(flyway.info().pending()).as("no pending migrations after migrate").isEmpty();
    }

    @Test
    @DisplayName("All engine migrations apply cleanly to an empty pgvector DB and validate")
    void migrationsApplyToEmptyDatabase() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(MIGRATIONS).load();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
    }

    @Test
    @DisplayName("Seeded known_issues start with NULL embeddings (no constant placeholders)")
    void seededIssuesHaveNoPlaceholderEmbeddings() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM known_issues", Integer.class);
        Integer nonNull = jdbc.queryForObject("SELECT COUNT(*) FROM known_issues WHERE embedding IS NOT NULL", Integer.class);
        assertThat(total).isEqualTo(50);
        assertThat(nonNull).isZero();
    }

    @Test
    @DisplayName("Dimension check passes when configured dimension matches vector(768)")
    void dimensionCheckPassesForMatchingDimension() throws Exception {
        new EmbeddingDimensionCheck(new JdbcTemplate(dataSource), dataSource, 768)
                .run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("Dimension check fails fast when configured dimension differs from vector(768)")
    void dimensionCheckFailsOnMismatch() {
        EmbeddingDimensionCheck check = new EmbeddingDimensionCheck(new JdbcTemplate(dataSource), dataSource, 1536);
        assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector(768)")
                .hasMessageContaining("1536");
    }
}
