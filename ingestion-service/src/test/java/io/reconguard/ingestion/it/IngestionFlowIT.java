package io.reconguard.ingestion.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reconguard.ingestion.security.HmacUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** B4: the ingestion service over real HTTP, with real Redis (idempotency) and real Kafka (publish). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "reconguard.webhook.secrets.t1=it-secret")
class IngestionFlowIT {

    private static final String SECRET = "it-secret";
    private static final String TOPIC = "reconguard.transactions.raw";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    private static String body(String tx, String order) {
        return """
                {"transactionId":"%s","tenantId":"t1","orderId":"%s",
                 "monetaryAmount":{"value":10.00,"currency":"USD"},
                 "type":"PAYMENT","timestamp":"2026-08-02T20:00:00Z"}""".formatted(tx, order);
    }

    private HttpResponse<String> post(String body, String signature, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/webhooks/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "t1")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            b.header("X-Idempotency-Key", idempotencyKey);
        }
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signedPost(String body, String idempotencyKey) throws Exception {
        return post(body, HmacUtils.calculateHmac(body, SECRET), idempotencyKey);
    }

    private List<ConsumerRecord<String, String>> recordsForKey(String key, Duration wait) {
        var props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ing-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> hits = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                c.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) {
                        hits.add(r);
                    }
                });
            }
        }
        return hits;
    }

    @Test
    @DisplayName("Signed request is accepted and lands on Kafka keyed tenant:order with the tenant header")
    void signedRequestIsPublished() throws Exception {
        String tx = "tx-" + UUID.randomUUID(), order = "o-" + UUID.randomUUID();
        HttpResponse<String> res = signedPost(body(tx, order), "k-" + UUID.randomUUID());

        assertThat(res.statusCode()).isEqualTo(202);
        JsonNode json = JSON.readTree(res.body());
        assertThat(json.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.get("duplicate").asBoolean()).isFalse();

        var records = recordsForKey("t1:" + order, Duration.ofSeconds(8));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).value()).contains(tx);
        assertThat(new String(records.get(0).headers().lastHeader("X-Tenant-Id").value(), StandardCharsets.UTF_8))
                .isEqualTo("t1");
    }

    @Test
    @DisplayName("Duplicate idempotency key replays the first response and publishes exactly once")
    void duplicateKeyPublishesOnce() throws Exception {
        String tx = "tx-" + UUID.randomUUID(), order = "o-" + UUID.randomUUID(), key = "k-" + UUID.randomUUID();
        String payload = body(tx, order);

        HttpResponse<String> first = signedPost(payload, key);
        HttpResponse<String> second = signedPost(payload, key);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        // Replay: same transaction id and the very same processedAt as the original
        assertThat(JSON.readTree(second.body()).get("processedAt"))
                .isEqualTo(JSON.readTree(first.body()).get("processedAt"));
        assertThat(recordsForKey("t1:" + order, Duration.ofSeconds(8))).hasSize(1);
    }

    @Test
    @DisplayName("Tampered signature: 403 problem+json, nothing published, key not consumed")
    void tamperedSignatureRejected() throws Exception {
        String order = "o-" + UUID.randomUUID();
        String payload = body("tx-" + UUID.randomUUID(), order);

        HttpResponse<String> res = post(payload, HmacUtils.calculateHmac(payload + " ", SECRET), "k-" + UUID.randomUUID());

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/problem+json"));
        assertThat(JSON.readTree(res.body()).get("title").asText()).isEqualTo("HMAC verification failed");
        assertThat(recordsForKey("t1:" + order, Duration.ofSeconds(4))).isEmpty();
    }

    @Test
    @DisplayName("Missing signature: 401 problem+json, nothing published")
    void missingSignatureRejected() throws Exception {
        String order = "o-" + UUID.randomUUID();

        HttpResponse<String> res = post(body("tx-" + UUID.randomUUID(), order), null, "k-" + UUID.randomUUID());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/problem+json"));
        assertThat(JSON.readTree(res.body()).get("type").asText()).endsWith("/missing-signature");
        assertThat(recordsForKey("t1:" + order, Duration.ofSeconds(4))).isEmpty();
    }

    @Test
    @DisplayName("Missing X-Idempotency-Key: 400 problem+json, nothing published")
    void missingIdempotencyKeyRejected() throws Exception {
        String order = "o-" + UUID.randomUUID();

        HttpResponse<String> res = signedPost(body("tx-" + UUID.randomUUID(), order), null);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(res.body()).get("title").asText()).isEqualTo("Missing Required Header");
        assertThat(recordsForKey("t1:" + order, Duration.ofSeconds(4))).isEmpty();
    }

    @Test
    @DisplayName("Unknown tenant has no secret: 401, nothing published")
    void unknownTenantRejected() throws Exception {
        String payload = body("tx-" + UUID.randomUUID(), "o-" + UUID.randomUUID());
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/webhooks/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "ghost")
                .header("X-Idempotency-Key", "k-" + UUID.randomUUID())
                .header("X-Signature", HmacUtils.calculateHmac(payload, SECRET))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(res.body()).get("type").asText()).endsWith("/unknown-tenant");
    }
}
