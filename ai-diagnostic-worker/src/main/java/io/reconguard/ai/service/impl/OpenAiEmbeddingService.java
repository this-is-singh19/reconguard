package io.reconguard.ai.service.impl;

import io.reconguard.ai.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI embeddings (profile {@code openai}). The embedding options pin {@code dimensions=768}
 * (see application-openai.yml) so vectors still fit {@code known_issues.embedding vector(768)}.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "reconguard.ai.provider", havingValue = "openai")
public class OpenAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public OpenAiEmbeddingService(EmbeddingModel embeddingModel,
                                  @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
