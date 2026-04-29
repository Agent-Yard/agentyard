package com.lynxus.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynxus.platform.extension.ExtensionDefinitionRegistryNotReadyException;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.platform.extension.ExtensionManifestFetcher;
import com.lynxus.platform.extension.ExtensionRegistrationProperties;
import com.lynxus.platform.extension.ExtensionRegistrationService;
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountStatusRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import com.lynxus.platform.shared.ApiProblemException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IntegrationAccountServiceTest {
    @Test
    void shouldUseKeyedCredentialFingerprint() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> credential = Map.of("businessCode", "biz-001", "secretKey", "secret-001");

        var first = new IntegrationCredentialCrypto(objectMapper, "test-encryption-key-a").encrypt(credential);
        var second = new IntegrationCredentialCrypto(objectMapper, "test-encryption-key-b").encrypt(credential);

        assertTrue(first.fingerprint().startsWith("v1:"));
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void shouldStoreCredentialEncryptedAndExposeDecryptedCredentialOnlyForRuntime() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        StoredIntegrationAccount existing = storedAccount(
            "integration-account-runtime",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            IntegrationAccountStatus.ENABLED,
            Map.of("baseUrl", "https://vendor.example"),
            "external-secret-ref-must-not-leak",
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key")
                .encrypt(Map.of("businessCode", "biz-001", "secretKey", "secret-001"))
                .ciphertext(),
            "fingerprint-must-not-leak"
        );
        saved.set(existing);
        var account = service.getAccount(existing.id());
        var runtimeCredential = service.runtimeCredential(existing.id());

        assertTrue(account.hasExternalSecretRef());
        assertTrue(account.credentialConfigured());
        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, account.credentialStatus());
        assertFalse(saved.get().credentialCiphertext().contains("secret-001"));
        assertEquals(IntegrationAccountSubjectType.TOOL_CONNECTOR, runtimeCredential.subjectType());
        assertEquals("simple-http", runtimeCredential.subjectId());
        assertEquals("biz-001", runtimeCredential.credential().get("businessCode"));
        assertEquals("secret-001", runtimeCredential.credential().get("secretKey"));
    }

    @Test
    void createRequiresNameAndDefaultsNonCredentialLifecycleFields() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        assertThrows(IllegalArgumentException.class, () -> service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            " ",
            null,
            Map.of()
        )));

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            null,
            null
        ));

        assertEquals(IntegrationAccountSubjectType.TOOL_CONNECTOR, created.subjectType());
        assertEquals("simple-http", created.subjectId());
        assertEquals(IntegrationAccountStatus.ENABLED, created.status());
        assertEquals(IntegrationAccountCredentialStatus.NOT_CONFIGURED, created.credentialStatus());
        assertEquals(Map.of(), created.config());
        assertFalse(created.hasExternalSecretRef());
        assertFalse(created.credentialConfigured());
        assertEquals(Map.of(), saved.get().metadata());
        assertTrue(saved.get().name().length() <= 128);
    }

    @Test
    void createAndUpdateValidateSubjectAndAccountConfigSchema() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            readyDefinitionService()
        );

        ApiProblemException missingSubject = assertThrows(ApiProblemException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "missing.connector",
                "Vendor Account",
                null,
                Map.of()
            )
        ));
        assertEquals("INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND", missingSubject.code());

        ApiProblemException invalidConfig = assertThrows(ApiProblemException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http",
                "Vendor Account",
                null,
                Map.of("tenantId", "")
            )
        ));
        assertEquals("INTEGRATION_ACCOUNT_CONFIG_INVALID", invalidConfig.code());

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            null,
            Map.of("tenantId", "tenant-001")
        ));
        service.updateAccount(created.id(), new UpdateIntegrationAccountRequest(
            "Renamed",
            IntegrationAccountStatus.DISABLED,
            Map.of("tenantId", "tenant-002")
        ));

        assertEquals("Renamed", saved.get().name());
        assertEquals(IntegrationAccountStatus.DISABLED, saved.get().status());
        assertEquals("tenant-002", saved.get().config().get("tenantId"));
    }

    @Test
    void registryNotReadyPreventsCreateBeforeSubjectLookup() {
        IntegrationAccountService service = new IntegrationAccountService(
            mock(IntegrationAccountRepository.class),
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            notReadyDefinitionService()
        );

        assertThrows(ExtensionDefinitionRegistryNotReadyException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.CHANNEL_PROVIDER,
                "feishu",
                "Feishu Account",
                null,
                Map.of()
            )
        ));
    }

    @Test
    void archiveAndStatusUpdateDoNotTouchCredentialFields() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        StoredIntegrationAccount existing = storedAccount(
            "integration-account-status",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            "Feishu Account",
            IntegrationAccountStatus.ENABLED,
            Map.of("tenantId", "tenant-001"),
            null,
            "ciphertext",
            "fingerprint"
        );
        saved.set(existing);
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            readyDefinitionService()
        );

        var disabled = service.updateAccountStatus(existing.id(), new UpdateIntegrationAccountStatusRequest(IntegrationAccountStatus.DISABLED));
        assertEquals(IntegrationAccountStatus.DISABLED, disabled.status());
        assertEquals("ciphertext", saved.get().credentialCiphertext());
        assertEquals("fingerprint", saved.get().credentialFingerprint());

        var archived = service.archiveAccount(existing.id());
        assertEquals(IntegrationAccountStatus.ARCHIVED, archived.status());
        assertEquals("ciphertext", saved.get().credentialCiphertext());
        assertEquals("fingerprint", saved.get().credentialFingerprint());
    }

    private static ExtensionDefinitionService readyDefinitionService() {
        return definitionService(accountConfigSchema());
    }

    private static ExtensionDefinitionService emptySchemaDefinitionService() {
        return definitionService(Map.of("type", "object"));
    }

    private static ExtensionDefinitionService definitionService(Map<String, Object> accountConfigSchema) {
        ExtensionDefinitionService service = new ExtensionDefinitionService(
            registrationService(),
            manifestFetcher(accountConfigSchema),
            "internal-token"
        );
        ExtensionDefinitionRegistry registry = service.loadRegistry();
        if (!registry.ready()) {
            throw new AssertionError(registry.errors().toString());
        }
        return service;
    }

    private static ExtensionDefinitionService notReadyDefinitionService() {
        return new ExtensionDefinitionService(
            registrationService(),
            (manifestUrl, headers) -> {
                throw new IOException("registry unavailable");
            },
            "internal-token"
        );
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(new ExtensionRegistrationProperties(
            null,
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        ));
    }

    private static ExtensionManifestFetcher manifestFetcher(Map<String, Object> accountConfigSchema) {
        return (manifestUrl, headers) -> {
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(channelProviderDescriptor("feishu", accountConfigSchema)), List.of());
            }
            if (ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(), List.of(
                    toolConnectorDescriptor("business-code-secret-http", accountConfigSchema),
                    toolConnectorDescriptor("mcp", accountConfigSchema),
                    toolConnectorDescriptor("simple-http", accountConfigSchema)
                ));
            }
            throw new AssertionError("unexpected manifest fetch for " + registrationId);
        };
    }

    private static String manifest(List<Map<String, Object>> channelProviders, List<Map<String, Object>> toolConnectors) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", toolConnectors);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return new ObjectMapper().writeValueAsString(manifest);
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType, Map<String, Object> accountConfigSchema) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Feishu");
        descriptor.put("description", "Feishu description");
        descriptor.put("accountConfigSchema", accountConfigSchema);
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of("type", "object"));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", Map.of("sendOutbound", "/channel/send-outbound"));
        return descriptor;
    }

    private static Map<String, Object> toolConnectorDescriptor(String connectorType, Map<String, Object> accountConfigSchema) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("connectorType", connectorType);
        descriptor.put("title", "CRM");
        descriptor.put("description", "CRM description");
        descriptor.put("accountConfigSchema", accountConfigSchema);
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of("type", "object"));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("operationMappingSchema", Map.of("type", "object"));
        descriptor.put("operationMappingUiSchema", List.of());
        descriptor.put("endpoints", Map.of("invoke", "/tools/crm/invoke"));
        return descriptor;
    }

    private static Map<String, Object> accountConfigSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("tenantId"),
            "properties", Map.of(
                "tenantId", Map.of("type", "string", "minLength", 1)
            ),
            "additionalProperties", false
        );
    }

    private static StoredIntegrationAccount storedAccount(
        String id,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        String externalSecretRef,
        String credentialCiphertext,
        String credentialFingerprint
    ) {
        assertNotNull(id);
        java.time.Instant now = java.time.Instant.now();
        return new StoredIntegrationAccount(
            id,
            subjectType,
            subjectId,
            name,
            status,
            config,
            externalSecretRef,
            credentialCiphertext,
            credentialFingerprint,
            IntegrationAccountCredentialStatus.ACTIVE,
            Map.of("ignored", true),
            now,
            now
        );
    }
}
