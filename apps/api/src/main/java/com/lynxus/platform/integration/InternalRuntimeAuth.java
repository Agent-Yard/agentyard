package com.lynxus.platform.integration;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InternalRuntimeAuth {
    private final String expectedToken;

    public InternalRuntimeAuth(@Value("${lynxus.internal-auth.token}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    public void requireBearer(String authorization) {
        if (expectedToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal authentication is required");
        }
        String actualToken = authorization.substring(prefix.length()).trim();
        if (!Objects.equals(expectedToken, actualToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal authentication token");
        }
    }
}
