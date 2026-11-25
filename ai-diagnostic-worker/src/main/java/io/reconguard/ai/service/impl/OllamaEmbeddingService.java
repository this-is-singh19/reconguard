package io.reconguard.ai.service.impl;

import io.reconguard.ai.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "reconguard.ai.provider", havingValue = "ollama")
public class OllamaEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    private final String modelName;

    public OllamaEmbeddingService(EmbeddingModel embeddingModel,
                                  @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public float[] embed(String text) {
        log.debug("Generating Ollama local embedding for context length: {}", text.length());
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        log.debug("Generating Ollama local embeddings for batch of size: {}", texts.size());
        return embeddingModel.embed(texts);
    }
}