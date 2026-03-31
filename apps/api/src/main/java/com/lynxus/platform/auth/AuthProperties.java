package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.auth")
public record AuthProperties(
    Bootstrap bootstrap,
    Role defaultRole
) {
    public AuthProperties {
        bootstrap = bootstrap == null ? new Bootstrap("admin") : bootstrap;
        defaultRole = defaultRole == null ? Role.BUSINESS_USER : defaultRole;
    }

    public record Bootstrap(String username) {
        public Bootstrap {
            username = username == null || username.isBlank() ? "admin" : username.trim();
        }
    }
}
