package io.reconguard.reconciliation.it;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime wiring checks for the engine: virtual threads, and the consumer retry -> DLQ path
 * when the persistence layer fails.
 */
// This context mocks the repository, so it must not consume the topic the shared (real) context consumes:
// own raw topic (DLQ is derived as <raw>.DLQ) and own group, and the context is closed after the class.
@TestPropertySource(properties = {
        "reconguard.topics.raw-transactions=reconguard.runtime-it.raw",
        "spring.kafka.consumer.group-id=reconguard-runtime-it-group"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EngineRuntimeIntegrationTest extends AbstractEngineIT {

    private static final String RAW_TOPIC = "reconguard.runtime-it.raw";
    private static final String DLQ_TOPIC = RAW_TOPIC + ".DLQ";

    private static final String TENANT_ID = "tenant-runtime-1";

    @Autowired
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    private AsyncTaskExecutor applicationTaskExecutor;

    // Persistence is down for every save (Kafka and Redis are real): the consumer must retry, then publish to the DLQ.
    @MockitoBean
    private TransactionRepository transactionRepository;

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
        kafka.send(RAW_TOPIC, orderId, new TransactionEvent(
                txId,
                TENANT_ID,
                orderId,
                new MonetaryAmount(new BigDecimal("50.00"), "USD"),
                TransactionEventType.ORDER,
                Instant.now()
        ));

        var dlqRecord = awaitRecord(DLQ_TOPIC, r -> orderId.equals(r.key()));

        assertThat(dlqRecord.value()).contains(txId);
        assertThat(new String(dlqRecord.headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("db down");
        // 1 delivery + 3 retries (see KafkaConsumerConfig.MAX_RETRIES)
        verify(transactionRepository, times(4)).save(any(TransactionRecord.class));
    }

}
