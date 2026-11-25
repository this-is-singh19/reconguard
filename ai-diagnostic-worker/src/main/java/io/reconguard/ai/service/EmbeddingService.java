package io.reconguard.ai.service;

import java.util.List;

public interface EmbeddingService {
    /**
     * Generates a vector embedding float array for the given input text string.
     * Expected dimension: 768 (nomic-embed-text, matching pgvector column definition).
     */
    float[] embed(String text);

    /** Embedding model name, used as the metric tag. */
    default String modelName() {
        return "unknown";
    }

    /**
     * Batch embedding generation for multiple text prompts.
     */
    List<float[]> embedAll(List<String> texts);
}