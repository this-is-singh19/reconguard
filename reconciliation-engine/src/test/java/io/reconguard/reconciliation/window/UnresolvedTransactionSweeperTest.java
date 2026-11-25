package io.reconguard.reconciliation.window;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.reconciliation.consumer.FailurePublisher;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnresolvedTransactionSweeperTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FailurePublisher failurePublisher;

    @InjectMocks
    private WindowUnresolvedTransactionSweeper sweeper;

    @Test
    @DisplayName("Should transition stale UNRESOLVED records to FAILED and publish failure events")
    void shouldSweepStaleUnresolvedAndEmitEvents() {
        TransactionRecord staleRecord = new TransactionRecord();
        staleRecord.setId("tx-stale-1");
        staleRecord.setTenantId("tenant-acme");
        staleRecord.setOrderId("order-99");
        staleRecord.setAmount(new BigDecimal("200.00"));
        staleRecord.setCurrency("USD");
        staleRecord.setType("ORDER");
        staleRecord.setStatus("UNRESOLVED");
        staleRecord.setEventTimestamp(Instant.now().minusSeconds(600)); // 10 minutes old

        when(transactionRepository.findStaleUnresolved(any(Instant.class)))
                .thenReturn(List.of(staleRecord));

        sweeper.sweepStaleUnresolvedTransactions();

        verify(transactionRepository, times(1)).markAsFailed(List.of("tx-stale-1"));

        ArgumentCaptor<TransactionEvent> eventCaptor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(failurePublisher, times(1)).publishFailure(
                eq("tenant-acme"), eventCaptor.capture(), eq("Unmatched window expiration"));

        TransactionEvent published = eventCaptor.getValue();
        assertThat(published.transactionId()).isEqualTo("tx-stale-1");
        assertThat(published.orderId()).isEqualTo("order-99");
        assertThat(published.monetaryAmount().value()).isEqualByComparingTo("200.00");
        assertThat(published.monetaryAmount().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Should do nothing when no stale records are found")
    void shouldDoNothingWhenNoStaleRecordsFound() {
        when(transactionRepository.findStaleUnresolved(any(Instant.class)))
                .thenReturn(List.of());

        sweeper.sweepStaleUnresolvedTransactions();

        verify(transactionRepository, never()).markAsFailed(any());
        verify(failurePublisher, never()).publishFailure(anyString(), any(), anyString());
    }
}
