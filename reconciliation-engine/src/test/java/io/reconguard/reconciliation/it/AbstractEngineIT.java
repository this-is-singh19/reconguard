package io.reconguard.reconciliation.it;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/**
 * Shared real infrastructure for engine integration tests (B3): Postgres+pgvector with the real Flyway
 * migrations, Redis and Kafka. Containers are singletons for the JVM, so the Spring context is reused.
 * No H2, no embedded Kafka, no mocked Redis.
 */
@SpringBootTest
abstract class AbstractEngineIT {

    static final String RAW_TOPIC = "reconguard.transactions.raw";
    static final String DLQ_TOPIC = "reconguard.transactions.raw.DLQ";
    static final String FAILURES_TOPIC = "reconguard.transactions.failures";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("recon_it").withUsername("recon_user").withPassword("recon_pass");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("reconguard.kafka.partitions", () -> "3");
        // Tests call the sweeper explicitly; keep the scheduler out of the way
        r.add("reconguard.sweeper.interval-ms", () -> "3600000");
    }

    @Autowired
    protected KafkaTemplate<String, Object> kafka;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate stringRedis;

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM reconciliation_failures");
        jdbc.update("DELETE FROM transactions");
        // transactions.tenant_id is a FK to tenants: tenants must be provisioned before their events can persist
        for (String t : List.of("tenant-it", "tenant-A", "tenant-B")) {
            jdbc.update("INSERT INTO tenants (tenant_id, name) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING", t, t);
        }
        var keys = stringRedis.keys("reconguard:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedis.delete(keys);
        }
    }

    static String uid(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static TransactionEvent event(String tx, String tenant, String order, TransactionEventType type,
                                  String amount, String currency, Instant ts) {
        return new TransactionEvent(tx, tenant, order, new MonetaryAmount(new BigDecimal(amount), currency), type, ts);
    }

    protected void send(TransactionEvent e) {
        kafka.send(RAW_TOPIC, e.tenantId() + ":" + e.orderId(), e);
    }

    protected String txStatus(String tx) {
        List<String> r = jdbc.queryForList("SELECT status FROM transactions WHERE transaction_id = ?", String.class, tx);
        return r.isEmpty() ? null : r.get(0);
    }

    protected void awaitStatus(String tx, String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(txStatus(tx)).isEqualTo(expected));
    }

    /** Reads a topic from the beginning with a fresh group until a record matching the predicate shows up. */
    protected ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match) {
        var props = new java.util.HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> hit = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(() -> {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> r : records) {
                    if (match.test(r)) {
                        hit.add(r);
                    }
                }
                return !hit.isEmpty();
            });
        }
        return hit.get(0);
    }
}
