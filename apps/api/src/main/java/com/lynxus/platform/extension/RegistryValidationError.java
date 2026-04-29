package com.lynxus.platform.extension;

import java.util.Map;

public record RegistryValidationError(
    String code,
    String severity,
    String registrationId,
    String descriptorType,
    String descriptorId,
    String message,
    boolean retryable,
    Map<String, Object> details
) {}
