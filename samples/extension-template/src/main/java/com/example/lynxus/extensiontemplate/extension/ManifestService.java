package com.example.lynxus.extensiontemplate.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.extension.sdk.generated.protocol.model.ServiceManifestEnvelope;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.validation.ManifestValidationError;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public final class ManifestService {
    private static final String MANIFEST_RESOURCE = "extension-manifest.json";

    private final ServiceManifestEnvelope manifest;

    public ManifestService(ObjectMapper objectMapper) {
        String rawManifest = readManifestResource();
        ManifestValidationResult validation = ManifestValidator.validate(JsonDocuments.parse(rawManifest));
        if (!validation.valid()) {
            String errors = validation.errors()
                .stream()
                .map(ManifestService::formatError)
                .collect(Collectors.joining("; "));
            throw new IllegalStateException("extension manifest is invalid: " + errors);
        }
        try {
            this.manifest = objectMapper.readValue(rawManifest, ServiceManifestEnvelope.class);
        } catch (IOException exception) {
            throw new IllegalStateException("extension manifest is not valid JSON", exception);
        }
    }

    public ServiceManifestEnvelope manifest() {
        return manifest;
    }

    private static String readManifestResource() {
        ClassPathResource resource = new ClassPathResource(MANIFEST_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("extension manifest resource is missing: " + MANIFEST_RESOURCE, exception);
        }
    }

    private static String formatError(ManifestValidationError error) {
        return error.code() + " at " + error.path() + ": " + error.message();
    }
}
