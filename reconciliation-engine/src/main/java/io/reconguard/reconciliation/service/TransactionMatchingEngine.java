package io.reconguard.reconciliation.service;

import io.reconguard.common.model.MonetaryAmount;
import io.reconguard.common.model.TransactionEvent;
import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import io.reconguard.reconciliation.model.MatchResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

@Component
public class TransactionMatchingEngine {

    private final MatchingToleranceConfig toleranceConfig;

    public TransactionMatchingEngine(MatchingToleranceConfig toleranceConfig) {
        this.toleranceConfig = toleranceConfig;
    }

    public MatchResult evaluate(TransactionEvent orderEvent, TransactionEvent paymentEvent) {
        // 1. Validate Tenant & Order correlation
        if (!orderEvent.tenantId().equals(paymentEvent.tenantId())) {
            return MatchResult.unmatched("Tenant ID mismatch");
        }

        if (!orderEvent.orderId().equals(paymentEvent.orderId())) {
            return MatchResult.unmatched("Order ID mismatch");
        }

        // 2. Validate Currency Match
        MonetaryAmount orderMoney = orderEvent.monetaryAmount();
        MonetaryAmount paymentMoney = paymentEvent.monetaryAmount();

        if (!orderMoney.currency().equalsIgnoreCase(paymentMoney.currency())) {
            return MatchResult.unmatched(
                    String.format("Currency mismatch: %s vs %s", orderMoney.currency(), paymentMoney.currency())
            );
        }

        // 3. Time Window Drift Check
        Duration timeDifference = Duration.between(orderEvent.timestamp(), paymentEvent.timestamp()).abs();
        if (timeDifference.compareTo(toleranceConfig.maxTimeWindow()) > 0) {
            return MatchResult.unmatched(
                    String.format("Timestamp difference of %s exceeds allowed threshold of %s",
                            timeDifference, toleranceConfig.maxTimeWindow())
            );
        }

        // 4. Monetary Amount Variance Evaluation
        BigDecimal diff = orderMoney.value().subtract(paymentMoney.value()).abs();

        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return MatchResult.matched();
        } else if (diff.compareTo(toleranceConfig.maxAmountDifference()) <= 0) {
            return MatchResult.flagged(
                    String.format("Amount discrepancy of %s is within allowed tolerance (%s)",
                            diff, toleranceConfig.maxAmountDifference()),
                    diff
            );
        }

        return MatchResult.unmatched(
                String.format("Amount discrepancy of %s exceeds maximum allowed tolerance (%s)",
                        diff, toleranceConfig.maxAmountDifference())
        );
    }
}