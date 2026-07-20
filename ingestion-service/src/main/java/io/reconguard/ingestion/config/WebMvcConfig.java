package io.reconguard.ingestion.config;

import io.reconguard.ingestion.security.HmacSecurityInterceptor;
import io.reconguard.common.multitenancy.TenantInterceptor; // adjust package if needed
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final HmacSecurityInterceptor hmacSecurityInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor, HmacSecurityInterceptor hmacSecurityInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
        this.hmacSecurityInterceptor = hmacSecurityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. Order matters: Authenticate/Verify security first
        registry.addInterceptor(hmacSecurityInterceptor)
                .addPathPatterns("/api/v1/webhooks/**");

        // 2. Then set up Tenant Context
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/webhooks/**");
    }
}