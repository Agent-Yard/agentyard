package com.lynxus.platform.auth;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class AuthModels {
    private static final Comparator<Role> ROLE_PRIORITY = Comparator
        .comparingInt(Role::priority)
        .thenComparing(Enum::name);

    private AuthModels() {
    }

    public enum Role {
        PLATFORM_ADMIN,
        DOMAIN_ADMIN,
        DEVELOPER,
        BUSINESS_USER;

        int priority() {
            return switch (this) {
                case PLATFORM_ADMIN -> 0;
                case DOMAIN_ADMIN -> 1;
                case DEVELOPER -> 2;
                case BUSINESS_USER -> 3;
            };
        }
    }

    public enum AuthSource {
        LOCAL_BOOTSTRAP,
        EXTERNAL
    }

    public enum UserStatus {
        ACTIVE,
        DISABLED
    }

    public record PlatformUser(
        String id,
        String username,
        String displayName,
        String email,
        AuthSource authSource,
        String externalIssuer,
        String externalSubject,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<Role> roles
    ) {
        public PlatformUser {
            roles = List.copyOf(roles == null ? List.of() : roles);
        }

        public boolean isActive() {
            return status == UserStatus.ACTIVE;
        }
    }

    public record UserSession(
        String userId,
        String displayName,
        Role currentRole,
        List<Role> availableRoles
    ) {
        public UserSession {
            availableRoles = orderedRoles(availableRoles);
        }
    }

    public record ExternalIdentity(
        String issuer,
        String subject,
        String preferredUsername,
        String displayName,
        String email
    ) {
    }

    public record LogoutResponse(
        String postLogoutRedirectUrl
    ) {
    }

    static List<Role> orderedRoles(List<Role> roles) {
        return roles == null ? List.of() : roles.stream().sorted(ROLE_PRIORITY).toList();
    }

    static Role primaryRole(List<Role> roles) {
        return orderedRoles(roles).stream().findFirst().orElseThrow();
    }
}
