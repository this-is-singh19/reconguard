package io.reconguard.reconciliation.window;

import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UnresolvedTransactionSweeperTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private UnresolvedTransactionSweeper sweeper;

    @Test
    @DisplayName("Should transition stale UNRESOLVED records to FAILED and emit failure events to Kafka")
    void shouldSweepStaleUnresolvedAndEmitEvents() {
        TransactionRecord staleRecord = new TransactionRecord();
        staleRecord.setTransactionId("tx-stale-1");
        staleRecord.setTenantId("tenant-acme");
        staleRecord.setOrderId("order-99");
        staleRecord.setAmount(new BigDecimal("200.00"));
        staleRecord.setCurrency("USD");
        staleRecord.setStatus("UNRESOLVED");
        staleRecord.setEventTimestamp(Instant.now().minusSeconds(600)); // 10 minutes old

        when(transactionRepository.findStaleUnresolved(any(Instant.class)))
                .thenReturn(List.of(staleRecord));

        sweeper.sweepStaleUnresolvedTransactions();

        verify(transactionRepository, times(1)).markAsFailed(List.of("tx-stale-1"));
        verify(kafkaTemplate, times(1)).send(
                eq("reconguard.transactions.failures"),
                eq("tenant-acme"),
                eq(staleRecord)
        );
    }

    @Test
    @DisplayName("Should do nothing when no stale records are found")
    void shouldDoNothingWhenNoStaleRecordsFound() {
        when(transactionRepository.findStaleUnresolved(any(Instant.class)))
                .thenReturn(List.of());

        sweeper.sweepStaleUnresolvedTransactions();

        verify(transactionRepository, never()).markAsFailed(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }
}