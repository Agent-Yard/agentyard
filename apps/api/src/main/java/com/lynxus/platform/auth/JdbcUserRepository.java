package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUserRepository implements UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PlatformUser> findByUsername(String username) {
        return jdbcTemplate.query(
            """
                select id, username, display_name, email, auth_source, external_issuer, external_subject, status, created_at, updated_at, last_login_at
                from platform_user
                where username = ?
                """,
            (rs, rowNum) -> mapUser(rs),
            username
        ).stream().findFirst().map(this::withRoles);
    }

    @Override
    public Optional<PlatformUser> findByExternalIdentity(String externalIssuer, String externalSubject) {
        return jdbcTemplate.query(
            """
                select id, username, display_name, email, auth_source, external_issuer, external_subject, status, created_at, updated_at, last_login_at
                from platform_user
                where external_issuer = ? and external_subject = ?
                """,
            (rs, rowNum) -> mapUser(rs),
            externalIssuer,
            externalSubject
        ).stream().findFirst().map(this::withRoles);
    }

    @Override
    public Optional<PlatformUser> findById(String userId) {
        return jdbcTemplate.query(
            """
                select id, username, display_name, email, auth_source, external_issuer, external_subject, status, created_at, updated_at, last_login_at
                from platform_user
                where id = ?
                """,
            (rs, rowNum) -> mapUser(rs),
            userId
        ).stream().findFirst().map(this::withRoles);
    }

    @Override
    @Transactional
    public PlatformUser save(PlatformUser user) {
        jdbcTemplate.update(
            """
                insert into platform_user (
                    id, username, display_name, email, auth_source, external_issuer, external_subject, status, created_at, updated_at, last_login_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    username = excluded.username,
                    display_name = excluded.display_name,
                    email = excluded.email,
                    auth_source = excluded.auth_source,
                    external_issuer = excluded.external_issuer,
                    external_subject = excluded.external_subject,
                    status = excluded.status,
                    updated_at = excluded.updated_at,
                    last_login_at = excluded.last_login_at
                """,
            user.id(),
            user.username(),
            user.displayName(),
            user.email(),
            user.authSource().name(),
            user.externalIssuer(),
            user.externalSubject(),
            user.status().name(),
            writeTimestamp(user.createdAt()),
            writeTimestamp(user.updatedAt()),
            writeTimestamp(user.lastLoginAt())
        );
        jdbcTemplate.update("delete from platform_user_role_binding where user_id = ?", user.id());
        for (Role role : AuthModels.orderedRoles(user.roles())) {
            jdbcTemplate.update(
                "insert into platform_user_role_binding (user_id, role) values (?, ?) on conflict do nothing",
                user.id(),
                role.name()
            );
        }
        return findById(user.id()).orElseThrow(() -> new IllegalStateException("saved user not found: " + user.id()));
    }

    private PlatformUser withRoles(PlatformUser user) {
        return new PlatformUser(
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
            loadRoles(user.id())
        );
    }

    private List<Role> loadRoles(String userId) {
        return AuthModels.orderedRoles(jdbcTemplate.query(
            """
                select role
                from platform_user_role_binding
                where user_id = ?
                """,
            (rs, rowNum) -> Role.valueOf(rs.getString("role")),
            userId
        ));
    }

    private PlatformUser mapUser(ResultSet rs) throws SQLException {
        return new PlatformUser(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("email"),
            AuthSource.valueOf(rs.getString("auth_source")),
            rs.getString("external_issuer"),
            rs.getString("external_subject"),
            UserStatus.valueOf(rs.getString("status")),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at"),
            readInstant(rs, "last_login_at"),
            List.of()
        );
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp writeTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
