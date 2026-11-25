package io.reconguard.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String TENANT_ID_HEADER = "X-Tenant-Id";

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Extract or generate Correlation ID
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            // 2. Extract Tenant ID if present in HTTP header
            String tenantId = request.getHeader(TENANT_ID_HEADER);
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = "UNKNOWN";
            }

            // 3. Inject into Slf4j MDC Context
            MDC.put(MDC_CORRELATION_ID, correlationId);
            MDC.put(MDC_TENANT_ID, tenantId);

            // 4. Attach Correlation ID to response header for client traceability
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            filterChain.doFilter(request, response);
        } finally {
            // Always clear MDC context after execution to avoid thread-pool leakage
            MDC.clear();
        }
    }
}