package io.reconguard.reconciliation.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Row in {@code reconciliation_failures}, written when the sweeper marks a transaction FAILED (A2.8).
 * {@code context_embedding} (pgvector) is intentionally not mapped: the AI worker writes it with plain SQL.
 */
@Entity
@Table(name = "reconciliation_failures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationFailure {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    @Column(name = "raw_payload_context", nullable = false, columnDefinition = "TEXT")
    private String rawPayloadContext;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
