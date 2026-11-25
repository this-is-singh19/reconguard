package io.reconguard.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "reconguard.matching")
public record MatchingToleranceConfig(
        BigDecimal maxAmountDifference,
        Duration maxTimeWindow
) {
    public MatchingToleranceConfig {
        if (maxAmountDifference == null)
            maxAmountDifference = new BigDecimal("0.02");
        if (maxTimeWindow == null)
            maxTimeWindow = Duration.ofSeconds(120);
    }
}