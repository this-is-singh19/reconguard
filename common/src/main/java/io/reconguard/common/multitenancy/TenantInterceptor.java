package io.reconguard.common.multitenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class TenantInterceptor implements HandlerInterceptor {
    private static final String X_TENANT_ID = "X-Tenant-Id";

    /**
     * Resolves the tenant from {@code X-Tenant-Id}. A missing or blank header is rejected with 400 problem+json.
     * There is no default tenant (A4.1): a request without a tenant must not reach the business logic.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String tenantId = request.getHeader(X_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getOutputStream().write(("{\"type\":\"https://api.reconguard.io/errors/missing-tenant\","
                    + "\"title\":\"Missing Tenant\",\"status\":400,"
                    + "\"detail\":\"Required header 'X-Tenant-Id' is missing or blank.\"}")
                    .getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            return false;
        }
        TenantContext.setTenantId(tenantId.strip());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear(); // Vital step to prevent memory leaks across threads
    }
}
