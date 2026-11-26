package io.reconguard.ingestion.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Per-tenant webhook secrets (A4.1): {@code reconguard.webhook.secrets.<tenantId>=<secret>}.
 * Only a holder of a tenant's secret can produce a valid signature for that tenant. Startup fails when no
 * secret is configured or any configured secret is blank, so no shared or default secret can exist.
 */
@ConfigurationProperties(prefix = "reconguard.webhook")
public record WebhookSecurityProperties(Map<String, String> secrets) {

    public WebhookSecurityProperties {
        if (secrets == null || secrets.isEmpty()) {
            throw new IllegalStateException("reconguard.webhook.secrets must define at least one tenant secret");
        }
        secrets = Map.copyOf(secrets);
        secrets.forEach((tenant, secret) -> {
            if (tenant == null || tenant.isBlank()) {
                throw new IllegalStateException("reconguard.webhook.secrets has a blank tenant id");
            }
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("reconguard.webhook.secrets." + tenant + " must be a non-blank value");
            }
        });
    }

    /** Secret for the tenant, or null when the tenant has none (the request is then rejected). */
    public String secretFor(String tenantId) {
        return tenantId == null ? null : secrets.get(tenantId);
    }
}
