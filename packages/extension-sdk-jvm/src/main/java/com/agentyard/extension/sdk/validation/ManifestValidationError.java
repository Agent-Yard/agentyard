package com.agentyard.extension.sdk.validation;

public record ManifestValidationError(String code, String path, String message) {}
