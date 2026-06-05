package com.agentyard.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserSession;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    @Test
    void shouldReturnCurrentSessionFromResolver() {
        AuthService authService = new AuthService(
            () -> platformUser("user-admin", "admin", UserStatus.ACTIVE, List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER))
        );

        UserSession session = authService.currentSession();

        assertEquals("user-admin", session.userId());
        assertEquals("平台管理员", session.displayName());
        assertEquals(Role.PLATFORM_ADMIN, session.currentRole());
        assertEquals(List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER), session.availableRoles());
    }

    @Test
    void shouldRejectMissingUser() {
        AuthService authService = new AuthService(() -> {
            throw new IllegalStateException("current user not found: admin");
        });

        IllegalStateException error = assertThrows(IllegalStateException.class, authService::currentSession);

        assertEquals("current user not found: admin", error.getMessage());
    }

    @Test
    void shouldRejectDisabledUser() {
        AuthService authService = new AuthService(
            () -> platformUser("user-admin", "admin", UserStatus.DISABLED, List.of(Role.PLATFORM_ADMIN))
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, authService::currentSession);

        assertEquals("current user is disabled: admin", error.getMessage());
    }

    @Test
    void shouldRejectUserWithoutRoles() {
        AuthService authService = new AuthService(
            () -> platformUser("user-admin", "admin", UserStatus.ACTIVE, List.of())
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, authService::currentSession);

        assertEquals("current user has no roles: admin", error.getMessage());
    }

    private PlatformUser platformUser(String id, String username, UserStatus status, List<Role> roles) {
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        return new PlatformUser(
            id,
            username,
            "平台管理员",
            null,
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            status,
            now,
            now,
            null,
            roles
        );
    }
}
