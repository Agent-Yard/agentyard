package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public UserSession currentSession() {
        return new UserSession(
            "user-dev-admin",
            "Lynxus Developer",
            Role.DOMAIN_ADMIN,
            List.of(Role.PLATFORM_ADMIN, Role.DOMAIN_ADMIN, Role.DEVELOPER, Role.BUSINESS_USER)
        );
    }
}
