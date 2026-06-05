package com.agentyard.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JooqUserRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqUserRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new JooqUserRepository(database.dsl());
    }

    @Test
    void shouldRunMigrationsAndSeedBootstrapAdmin() {
        Integer userCount = ((Number) database.dsl().fetchOne("select count(*) from platform_user").get(0)).intValue();
        Integer adminRoleCount = ((Number) database.dsl()
            .fetchOne("select count(*) from platform_user_role_binding where user_id = 'user-admin' and role = 'PLATFORM_ADMIN'")
            .get(0)).intValue();

        PlatformUser admin = repository.findByUsername("admin").orElseThrow();

        assertEquals(1, userCount);
        assertEquals(1, adminRoleCount);
        assertEquals("user-admin", admin.id());
        assertEquals("平台管理员", admin.displayName());
        assertEquals(AuthSource.LOCAL_BOOTSTRAP, admin.authSource());
        assertEquals(UserStatus.ACTIVE, admin.status());
        assertEquals(List.of(Role.PLATFORM_ADMIN), admin.roles());
        assertNotNull(admin.createdAt());
    }

    @Test
    void shouldLoadAllRolesForUser() {
        database.dsl().execute("insert into platform_user_role_binding (user_id, role) values ('user-admin', 'DEVELOPER')");

        PlatformUser admin = repository.findByUsername("admin").orElseThrow();

        assertEquals(List.of(Role.PLATFORM_ADMIN, Role.DEVELOPER), admin.roles());
    }

    @Test
    void shouldPersistAndReloadExternalUser() {
        Instant now = Instant.parse("2026-03-31T12:00:00Z");
        PlatformUser saved = repository.save(new PlatformUser(
            "user-ext-1",
            "alice",
            "Alice",
            "alice@example.com",
            AuthSource.EXTERNAL,
            "https://issuer.example.com",
            "ext-subject-1",
            UserStatus.ACTIVE,
            now,
            now,
            null,
            List.of(Role.BUSINESS_USER, Role.DEVELOPER)
        ));

        PlatformUser loaded = repository.findByExternalIdentity("https://issuer.example.com", "ext-subject-1").orElseThrow();

        assertEquals("user-ext-1", saved.id());
        assertEquals("alice", loaded.username());
        assertEquals("alice@example.com", loaded.email());
        assertEquals("https://issuer.example.com", loaded.externalIssuer());
        assertEquals(List.of(Role.DEVELOPER, Role.BUSINESS_USER), loaded.roles());
        assertTrue(loaded.createdAt().isBefore(loaded.updatedAt()) || loaded.createdAt().equals(loaded.updatedAt()));
    }
}
