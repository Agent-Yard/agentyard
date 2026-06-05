package com.agentyard.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultUserProvisioningServiceTest {
    @Test
    void shouldCreateExternalUserWithDefaultRole() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        DefaultUserProvisioningService service = new DefaultUserProvisioningService(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/")
        );

        PlatformUser user = service.provisionExternalUser(new ExternalIdentity(
            "https://issuer.example.com",
            "ext-001",
            "alice",
            "Alice",
            "alice@example.com"
        ));

        assertNotNull(user.id());
        assertEquals("alice", user.username());
        assertEquals("Alice", user.displayName());
        assertEquals(AuthSource.EXTERNAL, user.authSource());
        assertEquals("https://issuer.example.com", user.externalIssuer());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals(List.of(Role.BUSINESS_USER), user.roles());
    }

    @Test
    void shouldReuseExistingExternalUserAndPreserveRoles() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        repository.save(new PlatformUser(
            "user-ext-1",
            "alice",
            "Alice Old",
            null,
            AuthSource.EXTERNAL,
            "https://issuer.example.com",
            "ext-001",
            UserStatus.ACTIVE,
            now,
            now,
            null,
            List.of(Role.DEVELOPER)
        ));
        DefaultUserProvisioningService service = new DefaultUserProvisioningService(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/")
        );

        PlatformUser user = service.provisionExternalUser(new ExternalIdentity(
            "https://issuer.example.com",
            "ext-001",
            "alice",
            "Alice New",
            "alice@example.com"
        ));

        assertEquals("user-ext-1", user.id());
        assertEquals("Alice New", user.displayName());
        assertEquals("alice@example.com", user.email());
        assertEquals(List.of(Role.DEVELOPER, Role.BUSINESS_USER), user.roles());
    }

    @Test
    void shouldPreserveDisabledStatusWhenUpdatingExistingExternalUser() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        repository.save(new PlatformUser(
            "user-ext-1",
            "alice",
            "Alice Old",
            null,
            AuthSource.EXTERNAL,
            "https://issuer.example.com",
            "ext-001",
            UserStatus.DISABLED,
            now,
            now,
            null,
            List.of(Role.DEVELOPER)
        ));
        DefaultUserProvisioningService service = new DefaultUserProvisioningService(
            repository,
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/")
        );

        PlatformUser user = service.provisionExternalUser(new ExternalIdentity(
            "https://issuer.example.com",
            "ext-001",
            "alice",
            "Alice New",
            "alice@example.com"
        ));

        assertEquals(UserStatus.DISABLED, user.status());
        assertEquals(List.of(Role.DEVELOPER, Role.BUSINESS_USER), user.roles());
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
