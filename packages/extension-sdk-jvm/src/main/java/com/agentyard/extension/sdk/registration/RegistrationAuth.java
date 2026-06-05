package com.agentyard.extension.sdk.registration;

import java.util.Objects;

public record RegistrationAuth(RegistrationAuthType type) {
    public RegistrationAuth {
        Objects.requireNonNull(type, "type");
    }
}
