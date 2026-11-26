package io.reconguard.ingestion;

import io.reconguard.ingestion.security.WebhookSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableConfigurationProperties(WebhookSecurityProperties.class)
// Scans the local ingestion package AND pulls in the shared components (like TenantInterceptor) from common
@ComponentScan(basePackages = {
        "io.reconguard.ingestion",
        "io.reconguard.common"
})
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}