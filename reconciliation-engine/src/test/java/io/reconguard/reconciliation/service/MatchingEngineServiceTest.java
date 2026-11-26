package io.reconguard.reconciliation.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        // lenient: not every test reads both tolerances
        lenient().when(toleranceConfig.maxAmountDifference()).thenReturn(new BigDecimal("0.02"));
        lenient().when(toleranceConfig.maxTimeWindow()).thenReturn(Duration.ofSeconds(120));

        matchingEngineService = new MatchingEngineService(
                transactionRepository, windowBufferService, toleranceConfig, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Should match and persist pair when matching counterparty is in window buffer")
    void shouldMatchWhenCounterpartyInWindowBuffer() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "100.01", baseTime.plusSeconds(10));
        TransactionEvent bufferedEvent = createEvent("tx-1", TransactionEventType.ORDER, "100.00", baseTime);

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(bufferedEvent);

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(windowBufferService).removeEvent(tenantId, orderId, TransactionEventType.ORDER);

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

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(null);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId))
                .thenReturn(List.of(dbRecord));
        when(transactionRepository.linkAndMatch(eq("tx-1"), eq("tx-2"), eq(tenantId), any(Instant.class))).thenReturn(1);

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(transactionRepository).save(any(TransactionRecord.class));
        verify(transactionRepository).linkAndMatch(eq("tx-1"), eq("tx-2"), eq(tenantId), any(Instant.class));
        verify(windowBufferService).removeEvent(tenantId, orderId, TransactionEventType.ORDER);
    }

    @Test
    @DisplayName("Should reject match and buffer when amount exceeds tolerance threshold")
    void shouldRejectMatchWhenAmountExceedsTolerance() {
        // Arrange
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "105.00", baseTime);
        TransactionEvent bufferedEvent = createEvent("tx-1", TransactionEventType.ORDER, "100.00", baseTime);

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(bufferedEvent);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(List.of());

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

        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(bufferedEvent);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(List.of());

        // Act
        matchingEngineService.reconcile(incomingEvent);

        // Assert
        verify(transactionRepository).save(any(TransactionRecord.class));
        verify(windowBufferService).bufferEvent(incomingEvent);
    }

    @Test
    @DisplayName("A3.12: a transaction id owned by another tenant is rejected, never overwritten")
    void shouldRejectTransactionIdOwnedByAnotherTenant() {
        TransactionEvent incomingEvent = createEvent("tx-9", TransactionEventType.PAYMENT, "100.00", baseTime);
        TransactionRecord foreign = TransactionRecord.builder()
                .id("tx-9").tenantId("other-tenant").orderId("o-x").amount(new BigDecimal("1.0000"))
                .currency("USD").type("ORDER").status("UNRESOLVED").eventTimestamp(baseTime).build();
        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(null);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(List.of());
        when(transactionRepository.findById("tx-9")).thenReturn(Optional.of(foreign));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> matchingEngineService.reconcile(incomingEvent))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionRepository, never()).save(any(TransactionRecord.class));
    }

    @Test
    @DisplayName("A3.5: with several unresolved legs, the one that matches within tolerance is chosen")
    void shouldPickMatchingCounterpartAmongSeveralUnresolvedLegs() {
        TransactionEvent incomingEvent = createEvent("tx-2", TransactionEventType.PAYMENT, "100.00", baseTime);
        TransactionRecord wrongAmount = TransactionRecord.builder()
                .id("tx-old").tenantId(tenantId).orderId(orderId).amount(new BigDecimal("55.0000"))
                .currency("USD").type("ORDER").status("UNRESOLVED").eventTimestamp(baseTime).build();
        TransactionRecord rightLeg = TransactionRecord.builder()
                .id("tx-1").tenantId(tenantId).orderId(orderId).amount(new BigDecimal("100.00"))
                .currency("USD").type("ORDER").status("UNRESOLVED").eventTimestamp(baseTime).build();
        when(windowBufferService.getBufferedCounterparty(tenantId, orderId, TransactionEventType.ORDER)).thenReturn(null);
        when(transactionRepository.findUnresolvedByOrderIdAndTenant(orderId, tenantId)).thenReturn(List.of(wrongAmount, rightLeg));
        when(transactionRepository.linkAndMatch(eq("tx-1"), eq("tx-2"), eq(tenantId), any(Instant.class))).thenReturn(1);

        matchingEngineService.reconcile(incomingEvent);

        verify(transactionRepository).linkAndMatch(eq("tx-1"), eq("tx-2"), eq(tenantId), any(Instant.class));
        verify(transactionRepository, never()).linkAndMatch(eq("tx-old"), any(), any(), any());
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