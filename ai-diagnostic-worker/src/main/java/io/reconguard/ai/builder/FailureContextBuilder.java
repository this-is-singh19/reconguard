package io.reconguard.ai.builder;

import io.reconguard.common.event.TransactionFailureEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class FailureContextBuilder {

    /**
     * Builds a structured text representation of a transaction failure suitable
     * for vector embedding and semantic similarity matching.
     */
    public String buildFailureContext(TransactionFailureEvent event) {
        if (event == null) {
            return "";
        }

        StringBuilder context = new StringBuilder();

        context.append("TRANSACTION_FAILURE_CONTEXT\n")
               .append("Tenant ID: ").append(sanitize(event.tenantId())).append("\n")
               .append("Transaction ID: ").append(sanitize(event.transactionId())).append("\n")
               .append("Order ID: ").append(sanitize(event.orderId())).append("\n")
               .append("Amount: ").append(event.amount() != null ? event.amount().toPlainString() : "N/A").append("\n")
               .append("Currency: ").append(sanitize(event.currency())).append("\n")
               .append("Status: ").append(sanitize(event.status())).append("\n");

        if (event.eventTimestamp() != null) {
            context.append("Event Timestamp: ").append(event.eventTimestamp().toString()).append("\n");
            long ageSeconds = Duration.between(event.eventTimestamp(), Instant.now()).getSeconds();
            context.append("Age Seconds: ").append(ageSeconds).append("\n");
        }

        if (event.failureReason() != null && !event.failureReason().isBlank()) {
            context.append("Failure Reason: ").append(event.failureReason().trim()).append("\n");
        } else {
            context.append("Failure Reason: Unmatched window expiration / missing pair event\n");
        }

        return context.toString();
    }

    private String sanitize(String value) {
        return value != null ? value.trim() : "UNKNOWN";
    }
}