package io.reconguard.ingestion.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the webhook secret has no hard-coded default: startup fails unless it is set explicitly. */
class WebhookSecretConfigTest {

    // PropertyPlaceholderAutoConfiguration makes unresolved ${...} strict, as in a real Boot app.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(HmacSecurityInterceptor.class);

    @Test
    void failsFastWhenSecretUnset() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failsFastWhenSecretBlank() {
        runner.withPropertyValues("reconguard.webhook.secret= ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startsWhenSecretExplicitlySet() {
        runner.withPropertyValues("reconguard.webhook.secret=test-secret")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(HmacSecurityInterceptor.class));
    }
}
