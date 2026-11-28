package io.reconguard.reconciliation.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import io.reconguard.reconciliation.repository.TransactionRepository;
import io.reconguard.reconciliation.window.WindowBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Boundary behaviour of the matching rules: inclusive tolerances, currency, same-side rejection. */
class MatchingRulesBoundaryTest {

    private static final String TENANT = "t1";
    private static final String ORDER = "o1";
    private static final Instant T0 = Instant.parse("2026-07-25T10:00:00Z");

    private TransactionRepository repo;
    private WindowBufferService buffer;
    private MatchingEngineService service;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        buffer = mock(WindowBufferService.class);
        MatchingToleranceConfig cfg = mock(MatchingToleranceConfig.class);
        when(cfg.maxAmountDifference()).thenReturn(new BigDecimal("0.02"));
        when(cfg.maxTimeWindow()).thenReturn(Duration.ofSeconds(120));
        service = new MatchingEngineService(repo, buffer, cfg, new SimpleMeterRegistry());
    }

    private static TransactionEvent ev(String id, TransactionEventType type, String amt, String ccy, Instant ts) {
        return new TransactionEvent(id, TENANT, ORDER, new MonetaryAmount(new BigDecimal(amt), ccy), type, ts);
    }

    private void incomingPaymentAgainstBufferedOrder(TransactionEvent buffered, TransactionEvent incoming) {
        when(buffer.getBufferedCounterparty(TENANT, ORDER, TransactionEventType.ORDER)).thenReturn(buffered);
        service.reconcile(incoming);
    }

    private void assertMatched() {
        verify(buffer).removeEvent(TENANT, ORDER, TransactionEventType.ORDER);
        verify(buffer, never()).bufferEvent(any());
    }

    private void assertNotMatched(TransactionEvent incoming) {
        verify(buffer, never()).removeEvent(any(), any(), any());
        verify(buffer).bufferEvent(incoming);
    }

    @Test
    void amountDifferenceExactlyAtToleranceMatches() {
        incomingPaymentAgainstBufferedOrder(
                ev("a", TransactionEventType.ORDER, "100.00", "USD", T0),
                ev("b", TransactionEventType.PAYMENT, "100.02", "USD", T0));
        assertMatched();
    }

    @Test
    void amountDifferenceJustOverToleranceDoesNotMatch() {
        TransactionEvent in = ev("b", TransactionEventType.PAYMENT, "100.03", "USD", T0);
        incomingPaymentAgainstBufferedOrder(ev("a", TransactionEventType.ORDER, "100.00", "USD", T0), in);
        assertNotMatched(in);
    }

    @Test
    void negativeAmountDifferenceIsAlsoWithinTolerance() {
        incomingPaymentAgainstBufferedOrder(
                ev("a", TransactionEventType.ORDER, "100.00", "USD", T0),
                ev("b", TransactionEventType.PAYMENT, "99.98", "USD", T0));
        assertMatched();
    }

    @Test
    void timeDifferenceExactlyAtWindowMatches() {
        incomingPaymentAgainstBufferedOrder(
                ev("a", TransactionEventType.ORDER, "100.00", "USD", T0),
                ev("b", TransactionEventType.PAYMENT, "100.00", "USD", T0.plusSeconds(120)));
        assertMatched();
    }

    @Test
    void timeDifferenceJustOverWindowDoesNotMatch() {
        TransactionEvent in = ev("b", TransactionEventType.PAYMENT, "100.00", "USD", T0.plusSeconds(121));
        incomingPaymentAgainstBufferedOrder(ev("a", TransactionEventType.ORDER, "100.00", "USD", T0), in);
        assertNotMatched(in);
    }

    @Test
    void incomingEarlierThanBufferedWithinWindowMatches() {
        incomingPaymentAgainstBufferedOrder(
                ev("a", TransactionEventType.ORDER, "100.00", "USD", T0),
                ev("b", TransactionEventType.PAYMENT, "100.00", "USD", T0.minusSeconds(120)));
        assertMatched();
    }

    @Test
    void currencyMismatchDoesNotMatch() {
        TransactionEvent in = ev("b", TransactionEventType.PAYMENT, "100.00", "EUR", T0);
        incomingPaymentAgainstBufferedOrder(ev("a", TransactionEventType.ORDER, "100.00", "USD", T0), in);
        assertNotMatched(in);
    }

    @Test
    void currencyComparisonIgnoresCase() {
        incomingPaymentAgainstBufferedOrder(
                ev("a", TransactionEventType.ORDER, "100.00", "usd", T0),
                ev("b", TransactionEventType.PAYMENT, "100.00", "USD", T0));
        assertMatched();
    }

    @Test
    void sameSideBufferedLegIsRejected() {
        TransactionEvent in = ev("b", TransactionEventType.PAYMENT, "100.00", "USD", T0);
        incomingPaymentAgainstBufferedOrder(ev("a", TransactionEventType.PAYMENT, "100.00", "USD", T0), in);
        assertNotMatched(in);
    }
}
