package io.reconguard.common.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingConverterTest {

    private final MaskingConverter converter = new MaskingConverter();

    private String mask(String in) {
        return converter.transform(null, in);
    }

    @Test
    void masksCardNumberPlainSpacedAndDashed() {
        assertThat(mask("card 4111111111111111 charged")).isEqualTo("card [MASKED] charged");
        assertThat(mask("card 4111 1111 1111 1111 charged")).doesNotContain("4111");
        assertThat(mask("card 4111-1111-1111-1111 charged")).doesNotContain("4111");
    }

    @Test
    void masksIban() {
        String out = mask("iban GB82WEST12345698765432 ok");
        assertThat(out).doesNotContain("GB82").contains("[MASKED]");
    }

    @Test
    void masksEmail() {
        assertThat(mask("user john.doe+x@example.com failed")).isEqualTo("user [MASKED] failed");
    }

    @Test
    void leavesShortNumbersAndPlainTextAlone() {
        assertThat(mask("order 12345 amount 100.00")).isEqualTo("order 12345 amount 100.00");
        assertThat(mask("reconciliation matched")).isEqualTo("reconciliation matched");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(mask(null)).isNull();
        assertThat(mask("")).isEmpty();
    }
}
