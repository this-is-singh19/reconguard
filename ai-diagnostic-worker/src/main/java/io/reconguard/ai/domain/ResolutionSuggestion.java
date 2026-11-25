package io.reconguard.ai.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "resolution_suggestions")
public class ResolutionSuggestion {

    @Id
    @Column(name = "suggestion_id", length = 64)
    private String suggestionId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "matched_issue_id", length = 64)
    private String matchedIssueId;

    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "reasoning", nullable = false, columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "suggested_action", nullable = false, columnDefinition = "TEXT")
    private String suggestedAction;

    /** True when the worker acted on the suggestion itself (AUTO_RESOLVED only). */
    @Column(name = "auto_applied", nullable = false)
    private boolean autoApplied;

    @Column(name = "failure_id")
    private java.util.UUID failureId;

    @Column(name = "created_at")
    private Instant createdAt;
}