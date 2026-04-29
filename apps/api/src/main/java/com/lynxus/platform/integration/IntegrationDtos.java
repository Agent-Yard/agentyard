package com.lynxus.platform.integration;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    public enum IntegrationAccountAvailabilityBlock {
        SUBJECT_MISMATCH,
        ACCOUNT_STATUS_NOT_ENABLED,
        CREDENTIAL_REVOKE_FAILED,
        CREDENTIAL_REVOKED
    }

    public enum IntegrationAccountAvailabilityRisk {
        CREDENTIAL_NOT_CONFIGURED,
        CREDENTIAL_VALIDATION_FAILED,
        CREDENTIAL_ROTATION_REQUIRED
    }

    public record IntegrationAccountAvailabilityDecision(
        String accountId,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        IntegrationAccountStatus status,
        IntegrationAccountCredentialStatus credentialStatus,
        IntegrationAccountAvailabilityBlock hardBlock,
        List<IntegrationAccountAvailabilityRisk> risks
    ) {
        public IntegrationAccountAvailabilityDecision {
            risks = risks == null || risks.isEmpty() ? List.of() : List.copyOf(risks);
        }

        public boolean available() {
            return hardBlock == null;
        }
    }

    public record IntegrationAccountRuntimeSnapshot(
        String accountId,
        String externalSecretRef,
        String name,
        IntegrationAccountStatus status,
        IntegrationAccountCredentialStatus credentialStatus,
        boolean credentialConfigured,
        List<IntegrationAccountAvailabilityRisk> risks
    ) {
        public IntegrationAccountRuntimeSnapshot {
            risks = risks == null || risks.isEmpty() ? List.of() : List.copyOf(risks);
        }
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
        Map<String, Object> config,
        Object credential
    ) {
        public CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType subjectType,
            String subjectId,
            String name,
            IntegrationAccountStatus status,
            Map<String, Object> config
        ) {
            this(subjectType, subjectId, name, status, config, null);
        }

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

    public record CreateIntegrationAccountCredentialRequest(Object credential) {
    }

    public record RotateIntegrationAccountCredentialRequest(Object credential) {
    }

    public record RemoteCredentialLifecycleRequest(
        Map<String, Object> descriptor,
        Map<String, Object> account,
        Map<String, Object> credential,
        Map<String, Object> traceContext
    ) {
        public RemoteCredentialLifecycleRequest {
            descriptor = immutableObjectMap(descriptor);
            account = immutableObjectMap(account);
            credential = credential == null ? null : immutableObjectMap(credential);
            traceContext = immutableObjectMap(traceContext);
        }

        public Map<String, Object> toWireBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("descriptor", descriptor);
            body.put("account", account);
            if (credential != null) {
                body.put("credential", credential);
            }
            body.put("traceContext", traceContext);
            return Collections.unmodifiableMap(body);
        }
    }

    public record RemoteCredentialLifecycleResponse(
        String externalSecretRef,
        IntegrationAccountCredentialStatus credentialStatus
    ) {
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
