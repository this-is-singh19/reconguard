package io.reconguard.common.exception;

public class HmacVerificationException extends RuntimeException {

    public HmacVerificationException(String tenantId) {
        super("HMAC verification failed for tenant: " + tenantId);
    }

    public HmacVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}