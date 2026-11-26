package io.reconguard.ingestion.exception;

/** The body's tenantId disagrees with the authenticated X-Tenant-Id (A4.2). Rejected, never silently replaced. */
public class TenantMismatchException extends RuntimeException {
    public TenantMismatchException(String message) {
        super(message);
    }
}
