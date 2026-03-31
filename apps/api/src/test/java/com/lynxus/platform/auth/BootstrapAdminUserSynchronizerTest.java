package com.lynxus.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BootstrapAdminUserSynchronizerTest {
    @Test
    void shouldCreateBootstrapUserWithConfiguredUsername() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        BootstrapAdminUserSynchronizer synchronizer = new BootstrapAdminUserSynchronizer(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("root"), Role.BUSINESS_USER, true, "/")
        );

        synchronizer.synchronizeBootstrapUser();

        PlatformUser user = repository.findById(BootstrapAdminUserSynchronizer.BOOTSTRAP_USER_ID).orElseThrow();
        assertEquals("root", user.username());
        assertEquals(AuthSource.LOCAL_BOOTSTRAP, user.authSource());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals(List.of(Role.PLATFORM_ADMIN), user.roles());
    }

    @Test
    void shouldUpdateExistingBootstrapUserToMatchConfiguredUsername() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        repository.save(new PlatformUser(
            BootstrapAdminUserSynchronizer.BOOTSTRAP_USER_ID,
            "admin",
            "平台管理员",
            null,
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            UserStatus.DISABLED,
            now,
            now,
            null,
            List.of(Role.DEVELOPER)
        ));
        BootstrapAdminUserSynchronizer synchronizer = new BootstrapAdminUserSynchronizer(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("root"), Role.BUSINESS_USER, true, "/")
        );

        synchronizer.synchronizeBootstrapUser();

        PlatformUser user = repository.findById(BootstrapAdminUserSynchronizer.BOOTSTRAP_USER_ID).orElseThrow();
        assertEquals("root", user.username());
        assertEquals(UserStatus.DISABLED, user.status());
        assertEquals(List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER), user.roles());
    }

    @Test
    void shouldRejectConfiguredBootstrapUsernameOwnedByAnotherUser() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        repository.save(new PlatformUser(
            "user-other",
            "root",
            "Other User",
            null,
            AuthSource.EXTERNAL,
            "https://issuer.example.com",
            "ext-001",
            UserStatus.ACTIVE,
            now,
            now,
            null,
            List.of(Role.BUSINESS_USER)
        ));
        BootstrapAdminUserSynchronizer synchronizer = new BootstrapAdminUserSynchronizer(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("root"), Role.BUSINESS_USER, true, "/")
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, synchronizer::synchronizeBootstrapUser);

        assertEquals("bootstrap username already assigned to another user: root", error.getMessage());
    }

    private static final class InMemoryUserRepository implements UserRepository {
        private final Map<String, PlatformUser> usersById = new LinkedHashMap<>();

        @Override
        public Optional<PlatformUser> findByUsername(String username) {
            return usersById.values().stream().filter(user -> user.username().equals(username)).findFirst();
        }

        @Override
        public Optional<PlatformUser> findByExternalIdentity(String externalIssuer, String externalSubject) {
            return usersById.values().stream()
                .filter(user -> externalIssuer != null && externalIssuer.equals(user.externalIssuer()))
                .filter(user -> externalSubject != null && externalSubject.equals(user.externalSubject()))
                .findFirst();
        }

        @Override
        public Optional<PlatformUser> findById(String userId) {
            return Optional.ofNullable(usersById.get(userId));
        }

        @Override
        public PlatformUser save(PlatformUser user) {
            PlatformUser copy = new PlatformUser(
                user.id(),
                user.username(),
                user.displayName(),
                user.email(),
                user.authSource(),
                user.externalIssuer(),
                user.externalSubject(),
                user.status(),
                user.createdAt(),
                user.updatedAt(),
                user.lastLoginAt(),
                user.roles()
            );
            usersById.put(copy.id(), copy);
            return copy;
        }
    }
}
