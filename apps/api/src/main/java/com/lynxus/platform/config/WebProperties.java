package com.lynxus.platform.config;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.web")
public record WebProperties(List<String> allowedOrigins) {
    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
        "http://localhost:5173",
        "http://127.0.0.1:5173"
    );

    public WebProperties {
        allowedOrigins = normalizeAllowedOrigins(allowedOrigins);
    }

    private static List<String> normalizeAllowedOrigins(List<String> rawOrigins) {
        if (rawOrigins == null) {
            return DEFAULT_ALLOWED_ORIGINS;
        }

        List<String> normalized = rawOrigins.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .distinct()
            .toList();

        return normalized.isEmpty() ? DEFAULT_ALLOWED_ORIGINS : normalized;
    }
}
