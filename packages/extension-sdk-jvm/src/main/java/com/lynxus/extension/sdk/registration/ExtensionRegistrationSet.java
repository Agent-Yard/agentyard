package com.lynxus.extension.sdk.registration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExtensionRegistrationSet(
    List<ExtensionRegistration> services,
    String registrationConfigDigest,
    Map<String, Object> canonicalInput
) {
    public ExtensionRegistrationSet {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(registrationConfigDigest, "registrationConfigDigest");
        Objects.requireNonNull(canonicalInput, "canonicalInput");
        services = List.copyOf(services);
        canonicalInput = Map.copyOf(canonicalInput);
    }
}
