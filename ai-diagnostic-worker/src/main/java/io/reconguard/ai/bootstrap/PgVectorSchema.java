package io.reconguard.ai.bootstrap;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helpers for reading the pgvector column layout of known_issues.
 * Startup runners use these; they are skipped on non-PostgreSQL databases (e.g. H2 in engine tests).
 */
final class PgVectorSchema {

    private static final Pattern VECTOR_TYPE = Pattern.compile("vector\\((\\d+)\\)");

    private PgVectorSchema() {
    }

    static boolean isPostgres(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        }
    }

    /** Returns N for a column declared as vector(N). Fails if the column is missing or not a vector. */
    static int embeddingColumnDimension(JdbcTemplate jdbc) {
        String type = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                WHERE a.attrelid = 'known_issues'::regclass
                  AND a.attname = 'embedding'
                  AND NOT a.attisdropped
                """, String.class);
        Matcher m = VECTOR_TYPE.matcher(type == null ? "" : type);
        if (!m.matches()) {
            throw new IllegalStateException("known_issues.embedding is not a vector column (found: " + type + ")");
        }
        return Integer.parseInt(m.group(1));
    }

    /** pgvector accepts the text form of a float array, e.g. "[0.1, 0.2]". */
    static String toVectorLiteral(float[] vector) {
        return Arrays.toString(vector);
    }
}
