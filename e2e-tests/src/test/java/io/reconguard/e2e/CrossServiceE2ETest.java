package io.reconguard.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * B6: the whole pipeline with the three real service jars as separate processes:
 * webhook (ingestion) -> Kafka -> reconcile (engine) -> failure event -> embedding + pgvector (AI worker).
 * Only the embedding model is faked (deterministic vectors); everything else is real.
 * <ul>
 *   <li>ORDER + PAYMENT in tolerance  -> MATCHED, no suggestion</li>
 *   <li>lone leg, high similarity     -> FAILED, suggestion AUTO_RESOLVED</li>
 *   <li>lone leg, low similarity      -> FAILED, suggestion NEEDS_REVIEW</li>
 * </ul>
 */
class CrossServiceE2ETest {

    private static final String TENANT = "e2e-tenant";
    private static final String SECRET = "e2e-secret";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("recon_e2e").withUsername("recon_user").withPassword("recon_pass");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static FakeOllama ollama;
    static ServiceProcess engine;
    static ServiceProcess worker;
    static ServiceProcess ingestion;
    static int ingestionPort;

    @BeforeAll
    static void startEverything() throws Exception {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        ollama = new FakeOllama();

        Map<String, String> db = new LinkedHashMap<>();
        db.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        db.put("spring.datasource.username", POSTGRES.getUsername());
        db.put("spring.datasource.password", POSTGRES.getPassword());
        Map<String, String> kafka = Map.of("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        Map<String, String> redis = Map.of(
                "spring.data.redis.host", REDIS.getHost(),
                "spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379)));

        // 1. Engine first: it owns the Flyway schema and declares the topics
        Map<String, String> engineProps = new LinkedHashMap<>();
        engineProps.putAll(db);
        engineProps.putAll(kafka);
        engineProps.putAll(redis);
        engineProps.put("reconguard.kafka.partitions", "3");
        engineProps.put("reconguard.sweeper.interval-ms", "2000");
        engine = ServiceProcess.start("engine", System.getProperty("e2e.engine.jar"), freePort(), engineProps);
        engine.awaitHealthy(Duration.ofSeconds(180));

        // transactions.tenant_id is a FK to tenants
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenants (tenant_id, name) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")) {
            ps.setString(1, TENANT);
            ps.setString(2, TENANT);
            ps.executeUpdate();
        }

        // 2. AI worker, pointed at the fake Ollama
        Map<String, String> workerProps = new LinkedHashMap<>();
        workerProps.putAll(db);
        workerProps.putAll(kafka);
        workerProps.put("spring.flyway.enabled", "false");
        workerProps.put("spring.ai.ollama.base-url", "http://127.0.0.1:" + ollama.port());
        worker = ServiceProcess.start("worker", System.getProperty("e2e.worker.jar"), freePort(), workerProps);
        worker.awaitHealthy(Duration.ofSeconds(180));

        // 3. Ingestion
        Map<String, String> ingestionProps = new LinkedHashMap<>();
        ingestionProps.putAll(kafka);
        ingestionProps.putAll(redis);
        ingestionProps.put("reconguard.webhook.secrets." + TENANT, SECRET);
        ingestionProps.put("management.zipkin.tracing.export.enabled", "false");
        ingestionPort = freePort();
        ingestion = ServiceProcess.start("ingestion", System.getProperty("e2e.ingestion.jar"), ingestionPort, ingestionProps);
        ingestion.awaitHealthy(Duration.ofSeconds(120));

        // Wait for every known issue to be embedded (startup bootstrap), then pin one to e0
        await().atMost(Duration.ofSeconds(60)).until(() ->
                scalar("SELECT COUNT(*) FROM known_issues WHERE embedding IS NULL") == 0);
        StringBuilder e0 = new StringBuilder("[1");
        for (int i = 1; i < FakeOllama.DIM; i++) {
            e0.append(",0");
        }
        e0.append("]");
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(
                "UPDATE known_issues SET embedding = CAST(? AS vector) WHERE issue_id = (SELECT MIN(issue_id) FROM known_issues)")) {
            ps.setString(1, e0.toString());
            ps.executeUpdate();
        }
    }

    @AfterAll
    static void stopEverything() {
        for (AutoCloseable c : new AutoCloseable[]{ingestion, worker, engine, ollama}) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        KAFKA.stop();
        REDIS.stop();
        POSTGRES.stop();
    }

    // ---- scenarios -------------------------------------------------------------------------------------

    @Test
    @DisplayName("ORDER + PAYMENT within tolerance via webhook end MATCHED, with no failure suggestion")
    void matched() throws Exception {
        String order = "e2e-match-" + shortId();
        String orderTx = "tx-o-" + shortId(), payTx = "tx-p-" + shortId();

        assertThat(webhook(orderTx, order, "ORDER", "100.00")).isEqualTo(202);
        assertThat(webhook(payTx, order, "PAYMENT", "100.01")).isEqualTo(202);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(text("SELECT status FROM transactions WHERE transaction_id = ?", orderTx)).isEqualTo("MATCHED");
            assertThat(text("SELECT status FROM transactions WHERE transaction_id = ?", payTx)).isEqualTo("MATCHED");
        });
        assertThat(text("SELECT matched_with_transaction_id FROM transactions WHERE transaction_id = ?", orderTx)).isEqualTo(payTx);
        assertThat(scalar("SELECT COUNT(*) FROM resolution_suggestions WHERE tenant_id = ? AND transaction_id IN (?, ?)",
                TENANT, orderTx, payTx)).isZero();
    }

    @Test
    @DisplayName("Unmatched leg with a near-identical known issue ends FAILED then AUTO_RESOLVED")
    void autoResolved() throws Exception {
        String tx = unmatchedLegThatExpires("e2e-SCENARIO_95-");

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(text("SELECT status FROM resolution_suggestions WHERE tenant_id = ? AND transaction_id = ?", TENANT, tx))
                        .isEqualTo("AUTO_RESOLVED"));
        assertThat(text("SELECT status FROM transactions WHERE transaction_id = ?", tx)).isEqualTo("FAILED");
        assertThat(scalar("SELECT COUNT(*) FROM reconciliation_failures WHERE tenant_id = ? AND transaction_id = ?", TENANT, tx))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Unmatched leg with no similar known issue ends FAILED then NEEDS_REVIEW")
    void needsReview() throws Exception {
        String tx = unmatchedLegThatExpires("e2e-SCENARIO_10-");

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(text("SELECT status FROM resolution_suggestions WHERE tenant_id = ? AND transaction_id = ?", TENANT, tx))
                        .isEqualTo("NEEDS_REVIEW"));
        assertThat(text("SELECT status FROM transactions WHERE transaction_id = ?", tx)).isEqualTo("FAILED");
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /**
     * Sends one ORDER leg that never gets a payment, then ages it past the 5-minute window (the sweeper itself
     * runs every 2s here). The order id carries the SCENARIO marker, which reaches the embedding text.
     */
    private String unmatchedLegThatExpires(String orderPrefix) throws Exception {
        String order = orderPrefix + shortId();
        String tx = "tx-" + shortId();
        assertThat(webhook(tx, order, "ORDER", "42.00")).isEqualTo(202);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(text("SELECT status FROM transactions WHERE transaction_id = ?", tx)).isEqualTo("UNRESOLVED"));
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(
                "UPDATE transactions SET created_at = ? WHERE transaction_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))));
            ps.setString(2, tx);
            ps.executeUpdate();
        }
        return tx;
    }

    private static int webhook(String tx, String order, String type, String amount) throws Exception {
        String body = """
                {"transactionId":"%s","tenantId":"%s","orderId":"%s",
                 "monetaryAmount":{"value":%s,"currency":"USD"},
                 "type":"%s","timestamp":"%s"}""".formatted(tx, TENANT, order, amount, type, Instant.now());
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + ingestionPort + "/api/v1/webhooks/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT)
                .header("X-Idempotency-Key", "k-" + UUID.randomUUID())
                .header("X-Signature", hmacHex(body, SECRET))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String hmacHex(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Connection jdbc() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String text(String sql, Object... args) throws Exception {
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static long scalar(String sql, Object... args) throws Exception {
        return Long.parseLong(text(sql, args));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
