package io.reconguard.ai.dto;

public interface VectorMatchProjection {
    String getIssueId();
    String getPatternName();
    String getCategory();
    String getDescription();
    String getRecommendedAction();
    Double getSimilarity();
}