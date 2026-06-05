package com.agentyard.extension.sdk.validation;

import java.util.List;

public record ManifestValidationResult(List<ManifestValidationError> errors) {
    public ManifestValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
