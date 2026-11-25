package io.reconguard.ai.service.impl;

import io.reconguard.ai.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "reconguard.ai.provider", havingValue = "ollama")
public class OllamaEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public OllamaEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
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