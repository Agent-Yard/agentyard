package com.agentyard.extension.sdk.registration;

import java.util.Objects;

public record ExtensionRegistration(
    String registrationId,
    RegistrationSource source,
    String baseUrl,
    RegistrationExposes exposes,
    RegistrationAuth auth
) {
    public ExtensionRegistration {
        requireNonBlank(registrationId, "registrationId");
        Objects.requireNonNull(source, "source");
        requireNonBlank(baseUrl, "baseUrl");
        Objects.requireNonNull(exposes, "exposes");
        Objects.requireNonNull(auth, "auth");
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
