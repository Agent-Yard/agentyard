package com.lynxus.channel.gateway.channel;

import java.util.regex.Pattern;

final class OutboundErrorSanitizer {
    private static final int MAX_LENGTH = 512;
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern VAULT_REF = Pattern.compile("vault://[^\\s,'\\\"}]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "(?i)externalSecretRef|authorization|accessToken|refreshToken|apiKey|privateKey|password|credential"
    );

    private OutboundErrorSanitizer() {
    }

    static String sanitize(Throwable error) {
        if (error == null) {
            return "outbound delivery failed";
        }
        return sanitize(error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName()
            : error.getMessage());
    }

    static String sanitize(String message) {
        String sanitized = message == null || message.isBlank() ? "outbound delivery failed" : message;
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer [redacted]");
        sanitized = VAULT_REF.matcher(sanitized).replaceAll("[redacted-secret-ref]");
        sanitized = SECRET_FIELD.matcher(sanitized).replaceAll("[redacted]");
        sanitized = sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.length() > MAX_LENGTH) {
            return sanitized.substring(0, MAX_LENGTH);
        }
        return sanitized;
    }
}
