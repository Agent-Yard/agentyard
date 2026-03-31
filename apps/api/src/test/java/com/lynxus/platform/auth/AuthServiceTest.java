package com.lynxus.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    @Test
    void shouldReturnCurrentSessionFromRepository() {
        AuthService authService = new AuthService(
            () -> "admin",
            new StubUserRepository(platformUser("user-admin", "admin", UserStatus.ACTIVE, List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER)))
        );

        UserSession session = authService.currentSession();

        assertEquals("user-admin", session.userId());
        assertEquals("平台管理员", session.displayName());
        assertEquals(Role.PLATFORM_ADMIN, session.currentRole());
        assertEquals(List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER), session.availableRoles());
    }

    @Test
    void shouldRejectMissingBootstrapUser() {
        AuthService authService = new AuthService(() -> "admin", new StubUserRepository(null));

        IllegalStateException error = assertThrows(IllegalStateException.class, authService::currentSession);

        assertEquals("current user not found: admin", error.getMessage());
    }

    @Test
    void shouldRejectDisabledBootstrapUser() {
        AuthService authService = new AuthService(
            () -> "admin",
            new StubUserRepository(platformUser("user-admin", "admin", UserStatus.DISABLED, List.of(Role.PLATFORM_ADMIN)))
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, authService::currentSession);

        assertEquals("current user is disabled: admin", error.getMessage());
    }

    @Test
    void shouldRejectBootstrapUserWithoutRoles() {
        AuthService authService = new AuthService(
            () -> "admin",
            new StubUserRepository(platformUser("user-admin", "admin", UserStatus.ACTIVE, List.of()))
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
            status,
            now,
            now,
            null,
            roles
        );
    }

    private static final class StubUserRepository implements UserRepository {
        private final PlatformUser user;

        private StubUserRepository(PlatformUser user) {
            this.user = user;
        }

        @Override
        public Optional<PlatformUser> findByUsername(String username) {
            return user != null && user.username().equals(username) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public Optional<PlatformUser> findByExternalSubject(String externalSubject) {
            return Optional.empty();
        }

        @Override
        public Optional<PlatformUser> findById(String userId) {
            return user != null && user.id().equals(userId) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public PlatformUser save(PlatformUser user) {
            throw new UnsupportedOperationException();
        }
    }
}
