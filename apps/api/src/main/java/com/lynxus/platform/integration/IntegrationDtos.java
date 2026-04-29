package com.lynxus.platform.integration;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IntegrationDtos {
    private IntegrationDtos() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum IntegrationAccountSubjectType {
        TOOL_CONNECTOR,
        CHANNEL_PROVIDER
    }

    public enum IntegrationAccountStatus {
        ENABLED,
        DISABLED,
        ARCHIVED
    }

    public enum IntegrationAccountCredentialStatus {
        NOT_CONFIGURED,
        ACTIVE,
        VALIDATION_FAILED,
        ROTATION_REQUIRED,
        REVOKE_FAILED,
        REVOKED
    }

    public record IntegrationAccountDto(
        String id,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        boolean hasExternalSecretRef,
        boolean credentialConfigured,
        IntegrationAccountCredentialStatus credentialStatus,
        Instant createdAt,
        Instant updatedAt
    ) {
        public IntegrationAccountDto {
            config = immutableObjectMap(config);
        }
    }

    public record StoredIntegrationAccount(
        String id,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        String externalSecretRef,
        String credentialCiphertext,
        String credentialFingerprint,
        IntegrationAccountCredentialStatus credentialStatus,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
    ) {
        public StoredIntegrationAccount {
            config = immutableObjectMap(config);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record CreateIntegrationAccountRequest(
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config
    ) {
        public CreateIntegrationAccountRequest {
            config = immutableObjectMap(config);
        }
    }

    public record UpdateIntegrationAccountRequest(
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config
    ) {
        public UpdateIntegrationAccountRequest {
            config = config == null ? null : immutableObjectMap(config);
        }
    }

    public record UpdateIntegrationAccountStatusRequest(IntegrationAccountStatus status) {
    }

    public record RuntimeIntegrationCredentialDto(
        String accountId,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        Map<String, Object> credential
    ) {
        public RuntimeIntegrationCredentialDto {
            config = immutableObjectMap(config);
            credential = immutableObjectMap(credential);
        }
    }
}
