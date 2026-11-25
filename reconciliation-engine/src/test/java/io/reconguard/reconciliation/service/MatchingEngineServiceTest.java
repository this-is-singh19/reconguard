package io.reconguard.reconciliation.service;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import io.reconguard.reconciliation.domain.TransactionRecord;
import io.reconguard.reconciliation.repository.TransactionRepository;
import io.reconguard.reconciliation.window.WindowBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingEngineServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WindowBufferService windowBufferService;

    @Mock
    private MatchingToleranceConfig toleranceConfig;

    private MatchingEngineService matchingEngineService;

    private final String tenantId = "tenant-100";
    private final String orderId = "order-555";
    private final Instant baseTime = Instant.parse("2026-07-25T10:00:00Z");

    @BeforeEach
    void setUp() {
        when(toleranceConfig.maxAmountDifference()).thenReturn(new BigDecimal("0.02"));
        when(toleranceConfig.maxTimeWindow()).thenReturn(Duration.ofSeconds(120));

        matchingEngineService = new MatchingEngineService(
                transactionRepository, windowBufferService, toleranceConfig);
    }

    @Test
    @DisplayName("Should match and persist pair when matching counterparty is in window buffer")
    void shouldMatchWhenCounterpartyInWindowBuffer() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "100.01", baseTime.plusSeconds(10));
        TransactionEvent bufferedEvent = createEvent("tx-1", TransactionEventType.ORDER, "100.00", baseTime);

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId)).thenReturn(bufferedEvent);

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(windowBufferService).removeEvent(tenantId, orderId);

        ArgumentCaptor<TransactionRecord> recordCaptor = ArgumentCaptor.forClass(TransactionRecord.class);
        verify(transactionRepository, times(2)).save(recordCaptor.capture());

        var savedRecords = recordCaptor.getAllValues();
        assertThat(savedRecords).hasSize(2);
        assertThat(savedRecords.get(0).getStatus()).isEqualTo("MATCHED");
        assertThat(savedRecords.get(1).getStatus()).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("Should match and update status when counterparty is in DB")
    void shouldMatchWhenCounterpartyInDatabase() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "100.00", baseTime);
        TransactionRecord dbRecord = TransactionRecord.builder()
                .id("tx-1")
                .tenantId(tenantId)
                .orderId(orderId)
                .amount(new BigDecimal("100.01"))
                .currency("USD")
                .type("ORDER")
                .status("UNRESOLVED")
                .eventTimestamp(baseTime)
                .build();

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId)).thenReturn(null);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId))
                .thenReturn(Optional.of(dbRecord));

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(transactionRepository).save(any(TransactionRecord.class));
        verify(transactionRepository).linkAndMatch(eq("tx-1"), eq("tx-2"), eq(tenantId), any(Instant.class));
        verify(windowBufferService).removeEvent(tenantId, orderId);
    }

    @Test
    @DisplayName("Should reject match and buffer when amount exceeds tolerance threshold")
    void shouldRejectMatchWhenAmountExceedsTolerance() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "105.00", baseTime);
        TransactionEvent bufferedEvent = createEvent("tx-1", TransactionEventType.ORDER, "100.00", baseTime);

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId)).thenReturn(bufferedEvent);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(Optional.empty());

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(transactionRepository, never()).linkAndMatch(any(), any(), any(), any());
        verify(transactionRepository).save(any(TransactionRecord.class));
        verify(windowBufferService).bufferEvent(incomingEvent);
    }

    @Test
    @DisplayName("Should reject match when timestamp difference exceeds 120 seconds tolerance")
    void shouldRejectMatchWhenTimestampExceedsTolerance() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "100.00", baseTime.plusSeconds(300));
        TransactionEvent bufferedEvent = createEvent("tx-1", TransactionEventType.ORDER, "100.00", baseTime);

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId)).thenReturn(bufferedEvent);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(Optional.empty());

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(transactionRepository).save(any(TransactionRecord.class));
        verify(windowBufferService).bufferEvent(incomingEvent);
    }

    private TransactionEvent createEvent(
            String txId,
            TransactionEventType type,
            String amount,
            Instant timestamp) {

        return new TransactionEvent(
                txId,
                tenantId,
                orderId,
                new MonetaryAmount(new BigDecimal(amount), "USD"),
                type,
                timestamp
        );
    }
}