package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class BootstrapAdminUserSynchronizer implements ApplicationRunner {
    static final String BOOTSTRAP_USER_ID = "user-admin";
    private static final String BOOTSTRAP_DISPLAY_NAME = "平台管理员";

    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    public BootstrapAdminUserSynchronizer(UserRepository userRepository, AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        synchronizeBootstrapUser();
    }

    void synchronizeBootstrapUser() {
        String configuredUsername = authProperties.bootstrap().username();
        userRepository.findByUsername(configuredUsername)
            .filter(user -> !BOOTSTRAP_USER_ID.equals(user.id()))
            .ifPresent(user -> {
                throw new IllegalStateException("bootstrap username already assigned to another user: " + configuredUsername);
            });

        PlatformUser existing = userRepository.findById(BOOTSTRAP_USER_ID).orElse(null);
        if (existing == null) {
            Instant now = Instant.now();
            userRepository.save(new PlatformUser(
                BOOTSTRAP_USER_ID,
                configuredUsername,
                BOOTSTRAP_DISPLAY_NAME,
                null,
                AuthSource.LOCAL_BOOTSTRAP,
                null,
                UserStatus.ACTIVE,
                now,
                now,
                null,
                List.of(Role.PLATFORM_ADMIN)
            ));
            return;
        }

        Set<Role> roles = new LinkedHashSet<>(existing.roles());
        roles.add(Role.PLATFORM_ADMIN);
        List<Role> synchronizedRoles = AuthModels.orderedRoles(List.copyOf(roles));
        String displayName = existing.displayName() == null || existing.displayName().isBlank()
            ? BOOTSTRAP_DISPLAY_NAME
            : existing.displayName();
        boolean unchanged = existing.username().equals(configuredUsername)
            && existing.authSource() == AuthSource.LOCAL_BOOTSTRAP
            && existing.externalSubject() == null
            && displayName.equals(existing.displayName())
            && synchronizedRoles.equals(AuthModels.orderedRoles(existing.roles()));
        if (unchanged) {
            return;
        }

        userRepository.save(new PlatformUser(
            existing.id(),
            configuredUsername,
            displayName,
            existing.email(),
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            existing.status(),
            existing.createdAt(),
            Instant.now(),
            existing.lastLoginAt(),
            synchronizedRoles
        ));
    }
}
