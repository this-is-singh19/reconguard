package io.reconguard.ingestion.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** A4.1: webhook secrets are per tenant and there is no default. Startup fails unless they are set explicitly. */
class WebhookSecretConfigTest {

    @EnableConfigurationProperties(WebhookSecurityProperties.class)
    static class Config {
    }

    // PropertyPlaceholderAutoConfiguration makes unresolved ${...} strict, as in a real Boot app.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Config.class, HmacSecurityInterceptor.class);

    @Test
    void failsFastWhenNoSecretConfigured() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failsFastWhenAnyTenantSecretBlank() {
        runner.withPropertyValues("reconguard.webhook.secrets.t1= ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startsWhenTenantSecretExplicitlySet() {
        runner.withPropertyValues("reconguard.webhook.secrets.t1=test-secret")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(HmacSecurityInterceptor.class));
    }

    @Test
    void unknownTenantHasNoSecret() {
        WebhookSecurityProperties props = new WebhookSecurityProperties(java.util.Map.of("t1", "s1"));
        assertThat(props.secretFor("t2")).isNull();
        assertThat(props.secretFor(null)).isNull();
        assertThat(props.secretFor("t1")).isEqualTo("s1");
    }
}
