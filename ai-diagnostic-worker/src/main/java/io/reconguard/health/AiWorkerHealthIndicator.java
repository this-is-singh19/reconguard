package io.reconguard.health;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("aiWorker")
public class AiWorkerHealthIndicator implements HealthIndicator {

    private final EmbeddingModel embeddingModel;

    public AiWorkerHealthIndicator(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Health health() {
        try {
            // Send a ping embedding to check if Ollama is accessible
            float[] result = embeddingModel.embed("health check");
            if (result != null && result.length > 0) {
                return Health.up()
                        .withDetail("ollamaModel", "nomic-embed-text")
                        .withDetail("vectorDimensions", result.length)
                        .build();
            }
            return Health.down().withDetail("reason", "Received empty response from Ollama").build();
        } catch (Exception e) {
            return Health.down(e).withDetail("reason", "Failed to connect to Ollama embedding service").build();
        }
    }
}