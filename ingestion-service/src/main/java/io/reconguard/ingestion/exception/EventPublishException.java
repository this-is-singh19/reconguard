package io.reconguard.ingestion.exception;

/** The event could not be acknowledged by the broker. The client may retry (A4.4). */
public class EventPublishException extends RuntimeException {
    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
