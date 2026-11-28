package io.reconguard.ingestion.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacUtilsTest {

    @Test
    void matchesRfc4231TestCase2() {
        assertThat(HmacUtils.calculateHmac("what do ya want for nothing?", "Jefe"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void deterministicLowercaseHex64() {
        String a = HmacUtils.calculateHmac("body", "secret");
        assertThat(a).isEqualTo(HmacUtils.calculateHmac("body", "secret")).matches("[0-9a-f]{64}");
    }

    @Test
    void differentBodyOrSecretChangesSignature() {
        String base = HmacUtils.calculateHmac("body", "secret");
        assertThat(HmacUtils.calculateHmac("body ", "secret")).isNotEqualTo(base);
        assertThat(HmacUtils.calculateHmac("body", "secret2")).isNotEqualTo(base);
    }
}
