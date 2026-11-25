package io.reconguard.common.logging;

import org.slf4j.MDC;

/**
 * Puts tenant / order / transaction ids into the SLF4J MDC for the duration of a Kafka listener call.
 * Use with try-with-resources so the keys are always removed, even when the listener throws.
 */
public final class TransactionMdc implements AutoCloseable {

    public static final String TENANT_ID = "tenantId";
    public static final String ORDER_ID = "orderId";
    public static final String TRANSACTION_ID = "transactionId";

    private TransactionMdc() {
    }

    public static TransactionMdc open(String tenantId, String orderId, String transactionId) {
        put(TENANT_ID, tenantId);
        put(ORDER_ID, orderId);
        put(TRANSACTION_ID, transactionId);
        return new TransactionMdc();
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @Override
    public void close() {
        MDC.remove(TENANT_ID);
        MDC.remove(ORDER_ID);
        MDC.remove(TRANSACTION_ID);
    }
}
