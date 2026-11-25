package io.reconguard.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HmacSecurityInterceptor implements HandlerInterceptor {

    private static final ObjectMapper PROBLEM_MAPPER = new ObjectMapper();

    // No default: startup fails when reconguard.webhook.secret is unset (fail fast).
    @Value("${reconguard.webhook.secret}")
    private String webhookSecret;

    @PostConstruct
    void validateSecret() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("reconguard.webhook.secret must be set to a non-blank value");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String incomingSignature = request.getHeader("X-Signature");

        if (incomingSignature == null || incomingSignature.isBlank()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Missing Signature",
                    "Missing X-Signature header", "missing-signature");
            return false;
        }

        // Fail closed: if the body was not cached by RequestCachingFilter we cannot verify.
        HttpServletRequest current = request;
        while (current instanceof HttpServletRequestWrapper w && !(current instanceof RequestCachingFilter.CachedBodyRequestWrapper)) {
            current = (HttpServletRequest) w.getRequest();
        }
        if (!(current instanceof RequestCachingFilter.CachedBodyRequestWrapper wrapper)) {
            writeProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "Signature Verification Unavailable",
                    "Request body unavailable for signature verification", "internal-error");
            return false;
        }

        String body = new String(wrapper.getCachedBody(), StandardCharsets.UTF_8);
        String expectedSignature = HmacUtils.calculateHmac(body, webhookSecret);

        // Constant-time comparison to mitigate timing attacks
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                incomingSignature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            writeProblem(response, HttpStatus.FORBIDDEN, "HMAC verification failed",
                    "Invalid webhook signature", "hmac-failure");
            return false;
        }

        return true;
    }

    /** Writes an RFC 7807 body (application/problem+json) so HMAC rejections match the rest of the API. */
    private void writeProblem(HttpServletResponse response, HttpStatus status, String title,
                              String detail, String typeSlug) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://api.reconguard.io/errors/" + typeSlug);
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("timestamp", Instant.now().toString());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(PROBLEM_MAPPER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }
}