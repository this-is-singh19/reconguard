package io.reconguard.reconciliation.service;

import io.reconguard.reconciliation.config.MatchingToleranceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingToleranceConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ToleranceConfiguration.class);

    @Test
    void bindsOverridesFromReconguardMatchingPrefix() {
        runner.withPropertyValues(
                        "reconguard.matching.max-amount-difference=0.07",
                        "reconguard.matching.max-time-window=45s")
                .run(ctx -> {
                    MatchingToleranceConfig cfg = ctx.getBean(MatchingToleranceConfig.class);
                    assertThat(cfg.maxAmountDifference()).isEqualByComparingTo("0.07");
                    assertThat(cfg.maxTimeWindow()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    @Test
    void mainApplicationYmlDefinesTheKeysThatTheConfigBinds() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);

        // The defaults in MatchingToleranceConfig equal the yml values, so check the keys exist
        // (a wrong key would silently bind to the default).
        assertThat(env.containsProperty("reconguard.matching.max-amount-difference")).isTrue();
        assertThat(env.containsProperty("reconguard.matching.max-time-window")).isTrue();

        MatchingToleranceConfig bound = Binder.get(env)
                .bind("reconguard.matching", MatchingToleranceConfig.class)
                .get();
        assertThat(bound.maxAmountDifference()).isEqualByComparingTo("0.02");
        assertThat(bound.maxTimeWindow()).isEqualTo(Duration.ofSeconds(120));
    }

    @EnableConfigurationProperties(MatchingToleranceConfig.class)
    static class ToleranceConfiguration {
    }
}
