package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserSession;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final CurrentUserResolver currentUserResolver;

    public AuthService(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    public UserSession currentSession() {
        PlatformUser user = currentUserResolver.resolveCurrentUser();
        if (!user.isActive()) {
            throw new IllegalStateException("current user is disabled: " + user.username());
        }
        List<Role> roles = AuthModels.orderedRoles(user.roles());
        if (roles.isEmpty()) {
            throw new IllegalStateException("current user has no roles: " + user.username());
        }
        return new UserSession(
            user.id(),
            user.displayName(),
            AuthModels.primaryRole(roles),
            roles
        );
    }
}
