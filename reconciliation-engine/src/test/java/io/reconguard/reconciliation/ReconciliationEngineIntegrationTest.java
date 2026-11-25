package io.reconguard.reconciliation;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
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

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

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