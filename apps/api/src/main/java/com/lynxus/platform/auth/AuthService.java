package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AtomicReference<Role> currentRole = new AtomicReference<>(Role.DOMAIN_ADMIN);

    public UserSession currentSession() {
        return new UserSession(
            "u-demo-domain-admin",
            "Lynxus Demo User",
            currentRole.get(),
            List.of(Role.PLATFORM_ADMIN, Role.DOMAIN_ADMIN, Role.DEVELOPER, Role.BUSINESS_USER)
        );
    }

    public UserSession switchRole(Role role) {
        currentRole.set(role);
        return currentSession();
    }
}
