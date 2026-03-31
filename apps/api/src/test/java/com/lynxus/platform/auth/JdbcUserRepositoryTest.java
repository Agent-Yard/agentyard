package com.lynxus.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcUserRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcUserRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
            .cleanDisabled(false)
            .dataSource(dataSource)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcUserRepository(jdbcTemplate);
    }

    @Test
    void shouldRunMigrationsAndSeedBootstrapAdmin() {
        Integer userCount = jdbcTemplate.queryForObject("select count(*) from platform_user", Integer.class);
        Integer adminRoleCount = jdbcTemplate.queryForObject(
            "select count(*) from platform_user_role_binding where user_id = 'user-admin' and role = 'PLATFORM_ADMIN'",
            Integer.class
        );

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
        jdbcTemplate.update("insert into platform_user_role_binding (user_id, role) values (?, ?)", "user-admin", "DEVELOPER");

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
