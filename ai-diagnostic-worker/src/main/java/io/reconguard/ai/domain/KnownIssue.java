package io.reconguard.ai.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "known_issues")
public class KnownIssue {

    @Id
    @Column(name = "issue_id", length = 64)
    private String issueId;

    @Column(name = "pattern_name", nullable = false, length = 128)
    private String patternName;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "recommended_action", nullable = false, columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}