package io.reconguard.reconciliation;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime wiring checks for the engine: virtual threads, and the consumer retry -> DLQ path
 * when the persistence layer fails.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "reconguard.transactions.raw",
                "reconguard.transactions.raw.DLQ"
        }
)
@ActiveProfiles("test")
@DirtiesContext
class EngineRuntimeIntegrationTest {

    private static final String RAW_TOPIC = "reconguard.transactions.raw";
    private static final String DLQ_TOPIC = "reconguard.transactions.raw.DLQ";
    private static final String TENANT_ID = "tenant-runtime-1";

    @Autowired
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    private AsyncTaskExecutor applicationTaskExecutor;

    // Persistence is down for every save: the consumer must retry, then publish to the DLQ.
    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubRedis() {
        // Window buffer: nothing buffered
        ValueOperations<String, Object> bufferOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(bufferOps);

        // Idempotency: every claim is fresh
        ValueOperations<String, String> claimOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(claimOps);
        when(claimOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("Virtual threads are enabled from the engine application.yml")
    void virtualThreadsAreEnabled() throws Exception {
        assertThat(applicationTaskExecutor).isInstanceOf(SimpleAsyncTaskExecutor.class);
        // The executor is the one Boot builds from spring.threads.virtual.enabled; check the thread it runs on
        Boolean onVirtualThread = applicationTaskExecutor
                .submit(() -> Thread.currentThread().isVirtual())
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(onVirtualThread).isTrue();
    }

    @Test
    @DisplayName("A persistence failure is retried, then the event lands on the DLQ (not silently committed)")
    void dbFailureEndsInRetryThenDlq() {
        when(transactionRepository.save(any(TransactionRecord.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        String txId = "TXN-DB-FAIL-" + UUID.randomUUID();
        String orderId = "ORD-DB-FAIL-" + UUID.randomUUID();
        kafkaTemplate.send(RAW_TOPIC, orderId, new TransactionEvent(
                txId,
                TENANT_ID,
                orderId,
                new MonetaryAmount(new BigDecimal("50.00"), "USD"),
                TransactionEventType.ORDER,
                Instant.now()
        ));

        ConsumerRecord<String, String> dlqRecord = awaitDlqRecord(orderId, Duration.ofSeconds(40));

        assertThat(dlqRecord.value()).contains(txId);
        assertThat(new String(dlqRecord.headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("db down");
        // 1 delivery + 3 retries (see KafkaConsumerConfig.MAX_RETRIES)
        verify(transactionRepository, times(4)).save(any(TransactionRecord.class));
    }

    private ConsumerRecord<String, String> awaitDlqRecord(String orderId, Duration timeout) {
        var props = KafkaTestUtils.consumerProps("dlq-assert-" + UUID.randomUUID(), "false", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DLQ_TOPIC));
            await().atMost(timeout).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> r : records) {
                    if (orderId.equals(r.key())) {
                        found.set(r);
                    }
                }
                assertThat(found.get()).isNotNull();
            });
        }
        return found.get();
    }
}
