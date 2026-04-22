package com.lynxus.persistence.auth;

import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

import static com.lynxus.persistence.jooq.Tables.PLATFORM_USER;
import static com.lynxus.persistence.jooq.Tables.PLATFORM_USER_ROLE_BINDING;

public final class PlatformUserStore {
    private final DSLContext dsl;

    public PlatformUserStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<PlatformUserData> findByUsername(String username) {
        return dsl.selectFrom(PLATFORM_USER)
            .where(PLATFORM_USER.USERNAME.eq(username))
            .fetchOptional(record -> withRoles(mapUser(record)));
    }

    public Optional<PlatformUserData> findByExternalIdentity(String externalIssuer, String externalSubject) {
        return dsl.selectFrom(PLATFORM_USER)
            .where(PLATFORM_USER.EXTERNAL_ISSUER.eq(externalIssuer))
            .and(PLATFORM_USER.EXTERNAL_SUBJECT.eq(externalSubject))
            .fetchOptional(record -> withRoles(mapUser(record)));
    }

    public Optional<PlatformUserData> findById(String userId) {
        return dsl.selectFrom(PLATFORM_USER)
            .where(PLATFORM_USER.ID.eq(userId))
            .fetchOptional(record -> withRoles(mapUser(record)));
    }

    public PlatformUserData save(PlatformUserData user) {
        dsl.insertInto(PLATFORM_USER)
            .set(PLATFORM_USER.ID, user.id())
            .set(PLATFORM_USER.USERNAME, user.username())
            .set(PLATFORM_USER.DISPLAY_NAME, user.displayName())
            .set(PLATFORM_USER.EMAIL, user.email())
            .set(PLATFORM_USER.AUTH_SOURCE, user.authSource())
            .set(PLATFORM_USER.EXTERNAL_ISSUER, user.externalIssuer())
            .set(PLATFORM_USER.EXTERNAL_SUBJECT, user.externalSubject())
            .set(PLATFORM_USER.STATUS, user.status())
            .set(PLATFORM_USER.CREATED_AT, JooqTimeSupport.toOffsetDateTime(user.createdAt()))
            .set(PLATFORM_USER.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(user.updatedAt()))
            .set(PLATFORM_USER.LAST_LOGIN_AT, JooqTimeSupport.toOffsetDateTime(user.lastLoginAt()))
            .onConflict(PLATFORM_USER.ID)
            .doUpdate()
            .set(PLATFORM_USER.USERNAME, user.username())
            .set(PLATFORM_USER.DISPLAY_NAME, user.displayName())
            .set(PLATFORM_USER.EMAIL, user.email())
            .set(PLATFORM_USER.AUTH_SOURCE, user.authSource())
            .set(PLATFORM_USER.EXTERNAL_ISSUER, user.externalIssuer())
            .set(PLATFORM_USER.EXTERNAL_SUBJECT, user.externalSubject())
            .set(PLATFORM_USER.STATUS, user.status())
            .set(PLATFORM_USER.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(user.updatedAt()))
            .set(PLATFORM_USER.LAST_LOGIN_AT, JooqTimeSupport.toOffsetDateTime(user.lastLoginAt()))
            .execute();

        dsl.deleteFrom(PLATFORM_USER_ROLE_BINDING)
            .where(PLATFORM_USER_ROLE_BINDING.USER_ID.eq(user.id()))
            .execute();

        for (String role : user.roles()) {
            dsl.insertInto(PLATFORM_USER_ROLE_BINDING)
                .set(PLATFORM_USER_ROLE_BINDING.USER_ID, user.id())
                .set(PLATFORM_USER_ROLE_BINDING.ROLE, role)
                .onConflictDoNothing()
                .execute();
        }
        return findById(user.id()).orElseThrow(() -> new IllegalStateException("saved user not found: " + user.id()));
    }

    private PlatformUserData withRoles(PlatformUserData user) {
        return new PlatformUserData(
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

    private List<String> loadRoles(String userId) {
        return dsl.select(PLATFORM_USER_ROLE_BINDING.ROLE)
            .from(PLATFORM_USER_ROLE_BINDING)
            .where(PLATFORM_USER_ROLE_BINDING.USER_ID.eq(userId))
            .fetch(PLATFORM_USER_ROLE_BINDING.ROLE);
    }

    private PlatformUserData mapUser(com.lynxus.persistence.jooq.tables.records.PlatformUserRecord record) {
        return new PlatformUserData(
            record.getId(),
            record.getUsername(),
            record.getDisplayName(),
            record.getEmail(),
            record.getAuthSource(),
            record.getExternalIssuer(),
            record.getExternalSubject(),
            record.getStatus(),
            JooqTimeSupport.toInstant(record.getCreatedAt()),
            JooqTimeSupport.toInstant(record.getUpdatedAt()),
            JooqTimeSupport.toInstant(record.getLastLoginAt()),
            List.of()
        );
    }

    public record PlatformUserData(
        String id,
        String username,
        String displayName,
        String email,
        String authSource,
        String externalIssuer,
        String externalSubject,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<String> roles
    ) {
    }
}
