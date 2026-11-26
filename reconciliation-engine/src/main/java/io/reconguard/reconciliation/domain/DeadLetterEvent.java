package io.reconguard.reconciliation.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A record that exhausted its retries and was parked on the DLQ (A3.3). */
@Entity
@Table(name = "dead_letter_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "original_topic")
    private String originalTopic;

    @Column(name = "dlq_topic", nullable = false)
    private String dlqTopic;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
