package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.auth")
public record AuthProperties(
    Bootstrap bootstrap,
    Role defaultRole,
    boolean devBootstrapEnabled,
    String loginSuccessPath
) {
    public AuthProperties {
        bootstrap = bootstrap == null ? new Bootstrap("admin") : bootstrap;
        defaultRole = defaultRole == null ? Role.BUSINESS_USER : defaultRole;
        loginSuccessPath = sanitizePath(loginSuccessPath);
    }

    public record Bootstrap(String username) {
        public Bootstrap {
            username = username == null || username.isBlank() ? "admin" : username.trim();
        }
    }

    private static String sanitizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String value = raw.trim();
        return value.startsWith("/") ? value : "/" + value;
    }
}
