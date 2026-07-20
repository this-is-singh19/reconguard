package io.reconguard.ingestion.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class HmacSecurityInterceptor implements HandlerInterceptor {

    @Value("${reconguard.webhook.secret:super-secret-webhook-key-123}")
    private String webhookSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String incomingSignature = request.getHeader("X-Signature");

        if (incomingSignature == null || incomingSignature.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-Signature header");
            return false;
        }

        if (request instanceof ContentCachingRequestWrapper wrapper) {
            // Read the cached body bytes
            byte[] buf = wrapper.getContentAsByteArray();
            String body = new String(buf, StandardCharsets.UTF_8);

            // Compute what the signature should be
            String expectedSignature = HmacUtils.calculateHmac(body, webhookSecret);

            // Constant-time verification comparison to mitigate timing attacks
            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                    incomingSignature.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid webhook signature");
                return false;
            }
        }

        return true;
    }
}