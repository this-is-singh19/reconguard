package io.reconguard.reconciliation.consumer;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.idempotency.IdempotencyGuard;
import io.reconguard.reconciliation.repository.TransactionRepository;
import io.reconguard.reconciliation.service.MatchingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionConsumerMdcTest {

    private final MatchingEngineService matching = mock(MatchingEngineService.class);
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionConsumer consumer = new TransactionConsumer(matching, guard, repository);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static TransactionEvent event() {
        return new TransactionEvent("tx-7", "tenant-q", "order-5",
                new MonetaryAmount(new BigDecimal("12.0000"), "USD"),
                TransactionEventType.PAYMENT, Instant.now());
    }

    @Test
    void mdcIsSetDuringReconcileAndClearedAfter() {
        when(guard.checkAndClaim(anyString(), anyString())).thenReturn(true);
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.getCopyOfContextMap());
            return null;
        }).when(matching).reconcile(any());

        consumer.consumeTransaction(event());

        assertThat(seen.get())
                .containsEntry("tenantId", "tenant-q")
                .containsEntry("orderId", "order-5")
                .containsEntry("transactionId", "tx-7");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void mdcIsClearedWhenReconcileFailsAndExceptionIsRethrown() {
        when(guard.checkAndClaim(anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(matching).reconcile(any());

        assertThatThrownBy(() -> consumer.consumeTransaction(event()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("transactionId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }
}
