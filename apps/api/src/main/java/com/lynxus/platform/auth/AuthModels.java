package com.lynxus.platform.auth;

import java.util.List;

public final class AuthModels {
    private AuthModels() {
    }

    public enum Role {
        PLATFORM_ADMIN,
        DOMAIN_ADMIN,
        DEVELOPER,
        BUSINESS_USER
    }

    public record UserSession(
        String userId,
        String displayName,
        Role currentRole,
        List<Role> availableRoles
    ) {
    }

    public record SwitchRoleRequest(Role role) {
    }
}
