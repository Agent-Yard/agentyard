package com.lynxus.platform.auth;

import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminCurrentUserResolver implements CurrentUserResolver {
    private final AuthProperties authProperties;

    public BootstrapAdminCurrentUserResolver(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public String resolveCurrentUsername() {
        return authProperties.bootstrap().username();
    }
}
