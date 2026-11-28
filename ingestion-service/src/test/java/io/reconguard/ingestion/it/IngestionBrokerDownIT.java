package io.reconguard.ingestion.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reconguard.ingestion.security.HmacUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** B4: broker unreachable. The service must answer 503 problem+json and release the idempotency key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "reconguard.webhook.secrets.t1=it-secret",
        // Nothing listens here: the send fails fast instead of waiting out the default 60s metadata block
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=4000",
        "spring.kafka.producer.properties.request.timeout.ms=2000"
})
class IngestionBrokerDownIT {

    private static final String SECRET = "it-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("Broker down: 503 problem+json, idempotency key released so a retry with the same key can publish")
    void brokerDownReturns503AndReleasesKey() throws Exception {
        String key = "k-" + UUID.randomUUID();
        String payload = """
                {"transactionId":"tx-1","tenantId":"t1","orderId":"o-1",
                 "monetaryAmount":{"value":10.00,"currency":"USD"},
                 "type":"PAYMENT","timestamp":"2026-08-02T20:00:00Z"}""";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/webhooks/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "t1")
                .header("X-Idempotency-Key", key)
                .header("X-Signature", HmacUtils.calculateHmac(payload, SECRET))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/problem+json"));
        JsonNode problem = JSON.readTree(res.body());
        assertThat(problem.get("title").asText()).isEqualTo("Event Broker Unavailable");
        assertThat(problem.get("type").asText()).endsWith("/publish-unavailable");
        assertThat(redis.hasKey("recon:ingest:idempotency:t1:" + key)).isFalse();
    }
}
