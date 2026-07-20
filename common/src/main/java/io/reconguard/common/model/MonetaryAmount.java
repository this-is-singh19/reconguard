package io.reconguard.common.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record MonetaryAmount(
        @NotNull(message = "Amount value is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal value,

        @NotBlank(message = "Currency code is required (e.g., USD, EUR)")
        String currency
) {
    /**
     * Compact constructor to normalize currency formatting (always uppercase).
     */
    public MonetaryAmount {
        if (currency != null) {
            currency = currency.strip().toUpperCase();
        }
    }
}