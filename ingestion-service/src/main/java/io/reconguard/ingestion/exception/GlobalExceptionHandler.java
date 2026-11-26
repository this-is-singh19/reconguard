package io.reconguard.ingestion.exception;

import io.reconguard.ingestion.exception.EventPublishException;
import io.reconguard.ingestion.exception.TenantMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import io.reconguard.common.exception.HmacVerificationException;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HmacVerificationException.class)
    public ResponseEntity<ProblemDetail> handleHmacFailure(HmacVerificationException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                ex.getMessage()
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/hmac-failure"));
        problemDetail.setTitle("HMAC verification failed");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing."
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/missing-header"));
        problemDetail.setTitle("Missing Required Header");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(MethodArgumentNotValidException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Validation failed for request payload."
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/validation-error"));
        problemDetail.setTitle("Invalid Request Payload");

        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        problemDetail.setProperty("errors", fieldErrors);
        problemDetail.setProperty("timestamp", Instant.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or is not valid JSON."
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/malformed-body"));
        problemDetail.setTitle("Malformed Request Body");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(TenantMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTenantMismatch(TenantMismatchException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/tenant-mismatch"));
        problemDetail.setTitle("Tenant Mismatch");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /** Broker did not ack. Nothing was published and the idempotency key was released, so the client may retry (A4.4). */
    @ExceptionHandler(EventPublishException.class)
    public ResponseEntity<ProblemDetail> handlePublishFailure(EventPublishException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Transaction could not be accepted right now. Retry with the same idempotency key."
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/publish-unavailable"));
        problemDetail.setTitle("Event Broker Unavailable");
        problemDetail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected ingestion error occurred."
        );
        problemDetail.setType(URI.create("https://api.reconguard.io/errors/internal-error"));
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setProperty("timestamp", Instant.now());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}