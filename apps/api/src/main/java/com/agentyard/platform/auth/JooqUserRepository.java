package com.agentyard.platform.auth;

import com.agentyard.persistence.auth.PlatformUserStore;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqUserRepository implements UserRepository {
    private final PlatformUserStore store;

    public JooqUserRepository(DSLContext dsl) {
        this.store = new PlatformUserStore(dsl);
    }

    @Override
    public Optional<AuthModels.PlatformUser> findByUsername(String username) {
        return store.findByUsername(username).map(this::toModel);
    }

    @Override
    public Optional<AuthModels.PlatformUser> findByExternalIdentity(String externalIssuer, String externalSubject) {
        return store.findByExternalIdentity(externalIssuer, externalSubject).map(this::toModel);
    }

    @Override
    public Optional<AuthModels.PlatformUser> findById(String userId) {
        return store.findById(userId).map(this::toModel);
    }

    @Override
    @Transactional
    public AuthModels.PlatformUser save(AuthModels.PlatformUser user) {
        return toModel(store.save(new PlatformUserStore.PlatformUserData(
            user.id(),
            user.username(),
            user.displayName(),
            user.email(),
            user.authSource().name(),
            user.externalIssuer(),
            user.externalSubject(),
            user.status().name(),
            user.createdAt(),
            user.updatedAt(),
            user.lastLoginAt(),
            user.roles().stream().map(Enum::name).toList()
        )));
    }

    private AuthModels.PlatformUser toModel(PlatformUserStore.PlatformUserData user) {
        return new AuthModels.PlatformUser(
            user.id(),
            user.username(),
            user.displayName(),
            user.email(),
            AuthModels.AuthSource.valueOf(user.authSource()),
            user.externalIssuer(),
            user.externalSubject(),
            AuthModels.UserStatus.valueOf(user.status()),
            user.createdAt(),
            user.updatedAt(),
            user.lastLoginAt(),
            orderedRoles(user.roles())
        );
    }

    private List<AuthModels.Role> orderedRoles(List<String> roles) {
        return AuthModels.orderedRoles(roles.stream().map(AuthModels.Role::valueOf).toList());
    }
}
