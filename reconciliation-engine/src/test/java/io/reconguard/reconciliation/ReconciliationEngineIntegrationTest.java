package io.reconguard.reconciliation;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

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
class ReconciliationEngineIntegrationTest {

    @Autowired
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    // WindowBufferService uses RedisTemplate; IdempotencyGuard uses StringRedisTemplate. Mock both.
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubRedis() {
        // Window buffer: nothing buffered, so matching falls through to Postgres
        ValueOperations<String, Object> bufferOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(bufferOps);

        // Idempotency: every transaction id is a fresh claim
        ValueOperations<String, String> claimOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(claimOps);
        when(claimOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private static final String TOPIC = "reconguard.transactions.raw";
    private static final String TENANT_ID = "tenant-space-99";

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
    }

    @Test
    @DisplayName("Should ingest and successfully match ORDER and PAYMENT events within tolerance")
    void testEndToEndSuccessfulReconciliation() {
        String orderId = "ORD-TEST-1001";

        TransactionEvent orderEvent = new TransactionEvent(
                "TXN-ORDER-1",
                TENANT_ID,
                orderId,
                new MonetaryAmount(new BigDecimal("100.00"), "USD"),
                TransactionEventType.ORDER,
                Instant.now()
        );

        TransactionEvent paymentEvent = new TransactionEvent(
                "TXN-PAYMENT-1",
                TENANT_ID,
                orderId,
                new MonetaryAmount(new BigDecimal("100.01"), "USD"),
                TransactionEventType.PAYMENT,
                Instant.now()
        );

        // Publish ORDER leg
        kafkaTemplate.send(TOPIC, orderEvent.orderId(), orderEvent);

        // Verify ORDER leg saved as UNRESOLVED
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionRecord orderRecord = transactionRepository.findById("TXN-ORDER-1").orElse(null);
            assertNotNull(orderRecord);
            assertEquals("UNRESOLVED", orderRecord.getStatus());
        });

        // Publish PAYMENT leg
        kafkaTemplate.send(TOPIC, paymentEvent.orderId(), paymentEvent);

        // Verify both legs transition to MATCHED and link bidirectionally
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionRecord orderRecord = transactionRepository.findById("TXN-ORDER-1").orElse(null);
            TransactionRecord paymentRecord = transactionRepository.findById("TXN-PAYMENT-1").orElse(null);

            assertNotNull(orderRecord);
            assertNotNull(paymentRecord);

            assertEquals("MATCHED", orderRecord.getStatus());
            assertEquals("MATCHED", paymentRecord.getStatus());

            assertEquals("TXN-PAYMENT-1", orderRecord.getMatchedWithTransactionId());
            assertEquals("TXN-ORDER-1", paymentRecord.getMatchedWithTransactionId());
        });
    }
}