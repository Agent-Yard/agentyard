package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.Role;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component("platformAuthorization")
public class PlatformAuthorization {
    private static final Set<Role> GOVERNANCE_ROLES = EnumSet.of(
        Role.PLATFORM_ADMIN,
        Role.DOMAIN_ADMIN,
        Role.DEVELOPER
    );

    private final AuthService authService;

    public PlatformAuthorization(AuthService authService) {
        this.authService = authService;
    }

    public boolean hasGovernanceAccess() {
        return hasAnyRole(GOVERNANCE_ROLES);
    }

    public boolean hasGovernanceWrite() {
        return hasAnyRole(GOVERNANCE_ROLES);
    }

    public boolean hasRuntimeAccess() {
        return hasAnyRole(EnumSet.copyOf(Arrays.asList(Role.values())));
    }

    private boolean hasAnyRole(Set<Role> allowedRoles) {
        return authService.currentSession().availableRoles().stream().anyMatch(allowedRoles::contains);
    }
}
