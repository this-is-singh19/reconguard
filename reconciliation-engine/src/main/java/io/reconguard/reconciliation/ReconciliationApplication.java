package io.reconguard.reconciliation;

import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The engine depends on :ai-diagnostic-worker (see its build.gradle), so the AI worker's packages are on the
 * classpath. They must not become engine beans: the AI Kafka listeners, the AI failure container factory, the
 * embedding bootstrap runners, the AI health indicator and the AI entities are all excluded here. The engine
 * publishes failures to the shared topic and the AI worker consumes them in its own app (AiWorkerApplication).
 * Spring AI model autoconfiguration is switched off in application.yml (spring.ai.model.*: none), so no default
 * embedding model is built inside the engine either.
 *
 * Declared as @SpringBootConfiguration + @EnableAutoConfiguration rather than @SpringBootApplication: that meta-annotation
 * carries its own unfiltered @ComponentScan, which Spring applies alongside an explicit one. The Boot default filters
 * (TypeExcludeFilter, AutoConfigurationExcludeFilter) are repeated here to keep the same scan behaviour.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "io.reconguard",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                        // AiWorkerApplication lives in the scanned root package and would otherwise scan everything.
                        "io\\.reconguard\\.AiWorkerApplication",
                        "io\\.reconguard\\.ai\\..*",
                        "io\\.reconguard\\.health\\..*",
                        "io\\.reconguard\\.controller\\..*"
                })
        }
)
@EntityScan(basePackages = "io.reconguard.reconciliation")
@EnableJpaRepositories(basePackages = "io.reconguard.reconciliation")
@EnableConfigurationProperties(MatchingToleranceConfig.class)
@EnableScheduling
public class ReconciliationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
