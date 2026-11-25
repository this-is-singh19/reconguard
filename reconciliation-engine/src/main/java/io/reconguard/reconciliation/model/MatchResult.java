package io.reconguard.reconciliation.model;

import java.math.BigDecimal;

public record MatchResult(
        MatchStatus status,
        String reason,
        BigDecimal amountDiscrepancy
) {
    public enum MatchStatus {
        MATCHED,
        FLAGGED,
        UNMATCHED
    }

    public static MatchResult matched() {
        return new MatchResult(MatchStatus.MATCHED, "Exact match", BigDecimal.ZERO);
    }

    public static MatchResult flagged(String reason, BigDecimal discrepancy) {
        return new MatchResult(MatchStatus.FLAGGED, reason, discrepancy);
    }

    public static MatchResult unmatched(String reason) {
        return new MatchResult(MatchStatus.UNMATCHED, reason, null);
    }
}