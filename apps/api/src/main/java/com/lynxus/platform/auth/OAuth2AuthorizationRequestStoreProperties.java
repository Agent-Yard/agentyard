package com.lynxus.platform.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.auth.oauth2.authorization-request")
public record OAuth2AuthorizationRequestStoreProperties(Duration ttl) {
    public OAuth2AuthorizationRequestStoreProperties {
        ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(10) : ttl;
    }
}
