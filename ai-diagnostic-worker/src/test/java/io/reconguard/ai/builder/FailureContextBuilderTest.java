package io.reconguard.ai.builder;

import io.reconguard.common.event.TransactionFailureEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FailureContextBuilderTest {

    private final FailureContextBuilder builder = new FailureContextBuilder();

    @Test
    void includesGatewayErrorCodeAndRegionWhenPresent() {
        TransactionFailureEvent event = new TransactionFailureEvent(
                "tx-1", "tenant-a", "order-1", new BigDecimal("10.0000"), "USD", "FAILED",
                "timeout", Instant.now(), "stripe", "card_declined", "eu-west-1");

        String context = builder.buildFailureContext(event);

        assertThat(context).contains("Gateway: stripe\n");
        assertThat(context).contains("Error Code: card_declined\n");
        assertThat(context).contains("Region: eu-west-1\n");
    }

    @Test
    void omitsOptionalLinesWhenFieldsAreNullOrBlank() {
        TransactionFailureEvent legacy = new TransactionFailureEvent(
                "tx-1", "tenant-a", "order-1", new BigDecimal("10.0000"), "USD", "FAILED",
                null, Instant.now());
        TransactionFailureEvent blank = new TransactionFailureEvent(
                "tx-1", "tenant-a", "order-1", new BigDecimal("10.0000"), "USD", "FAILED",
                null, Instant.now(), "  ", "", null);

        assertThat(builder.buildFailureContext(legacy))
                .doesNotContain("Gateway:")
                .doesNotContain("Error Code:")
                .doesNotContain("Region:");
        assertThat(builder.buildFailureContext(blank))
                .doesNotContain("Gateway:")
                .doesNotContain("Error Code:")
                .doesNotContain("Region:");
    }
}
