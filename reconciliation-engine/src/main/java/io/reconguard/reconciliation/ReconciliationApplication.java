package io.reconguard.reconciliation;

import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.reconguard")
@EntityScan(basePackages = "io.reconguard")
@EnableJpaRepositories(basePackages = "io.reconguard")
@EnableConfigurationProperties(MatchingToleranceConfig.class)
@EnableScheduling
public class ReconciliationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}