package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final CurrentUserResolver currentUserResolver;
    private final UserRepository userRepository;

    public AuthService(CurrentUserResolver currentUserResolver, UserRepository userRepository) {
        this.currentUserResolver = currentUserResolver;
        this.userRepository = userRepository;
    }

    public UserSession currentSession() {
        String username = currentUserResolver.resolveCurrentUsername();
        PlatformUser user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("current user not found: " + username));
        if (!user.isActive()) {
            throw new IllegalStateException("current user is disabled: " + username);
        }
        List<Role> roles = AuthModels.orderedRoles(user.roles());
        if (roles.isEmpty()) {
            throw new IllegalStateException("current user has no roles: " + username);
        }
        return new UserSession(
            user.id(),
            user.displayName(),
            AuthModels.primaryRole(roles),
            roles
        );
    }
}
