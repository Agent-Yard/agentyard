package com.example.lynxus.extensiontemplate.protocol;

public record ExtensionRequestContext(
    String registrationId,
    String descriptorType,
    String descriptorId,
    String traceId,
    String requestId,
    String idempotencyKey
) {}
