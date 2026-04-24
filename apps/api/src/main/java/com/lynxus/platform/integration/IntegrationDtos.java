package com.lynxus.platform.integration;

import com.lynxus.contracts.runtime.WorkflowContracts.ToolConnectorType;
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

    public enum IntegrationAccountStatus {
        ACTIVE,
        INACTIVE
    }

    public record IntegrationAccountDto(
        String id,
        ToolConnectorType connectorType,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        boolean credentialConfigured,
        Instant createdAt,
        Instant updatedAt
    ) {
        public IntegrationAccountDto {
            config = immutableObjectMap(config);
        }
    }

    public record StoredIntegrationAccount(
        String id,
        ToolConnectorType connectorType,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        String credentialCiphertext,
        String credentialFingerprint,
        Instant createdAt,
        Instant updatedAt
    ) {
        public StoredIntegrationAccount {
            config = immutableObjectMap(config);
        }
    }

    public record CreateIntegrationAccountRequest(
        ToolConnectorType connectorType,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        Map<String, Object> credential
    ) {
        public CreateIntegrationAccountRequest {
            config = immutableObjectMap(config);
            credential = immutableObjectMap(credential);
        }
    }

    public record UpdateIntegrationAccountRequest(
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        Map<String, Object> credential
    ) {
        public UpdateIntegrationAccountRequest {
            config = config == null ? null : immutableObjectMap(config);
            credential = credential == null ? null : immutableObjectMap(credential);
        }
    }

    public record RuntimeIntegrationCredentialDto(
        String accountId,
        ToolConnectorType connectorType,
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
