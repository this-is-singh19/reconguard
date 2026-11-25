package io.reconguard.common.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaskingConverter extends CompositeConverter<ILoggingEvent> {

    // Regex for Credit Card Numbers (13-19 digits, space/dash separated)
    private static final Pattern CREDIT_CARD_PATTERN =
            Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    // Regex for IBANs (2 letters followed by 2 digits and 11-30 alphanumeric characters)
    private static final Pattern IBAN_PATTERN =
            Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b", Pattern.CASE_INSENSITIVE);

    // Regex for Email Addresses
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}\\b");

    private static final String MASK_REPLACEMENT = "[MASKED]";

    @Override
    protected String transform(ILoggingEvent event, String in) {
        if (in == null || in.isEmpty()) {
            return in;
        }

        // Apply masking sequentially across PII data formats
        String masked = CREDIT_CARD_PATTERN.matcher(in).replaceAll(MASK_REPLACEMENT);
        masked = IBAN_PATTERN.matcher(masked).replaceAll(MASK_REPLACEMENT);
        masked = EMAIL_PATTERN.matcher(masked).replaceAll(MASK_REPLACEMENT);

        return masked;
    }
}