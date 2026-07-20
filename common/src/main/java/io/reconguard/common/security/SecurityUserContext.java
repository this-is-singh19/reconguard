package io.reconguard.common.security;

import io.reconguard.common.multitenancy.TenantContext;
import java.util.Collections;
import java.util.Set;

/**
 * Utility to extract details from authenticated users across all processing modules.
 */
public class SecurityUserContext {

    public record AuthenticatedUser(String username, String tenantId, Set<String> roles) {}

    public static AuthenticatedUser getSystemUser() {
        // Mock fallback/system level details.
        // Later, swap this with your actual SecurityContextHolder mapping.
        return new AuthenticatedUser(
                "system-orchestrator",
                TenantContext.getTenantId(),
                Collections.singleton("ROLE_SYSTEM_ADMIN")
        );
    }
}