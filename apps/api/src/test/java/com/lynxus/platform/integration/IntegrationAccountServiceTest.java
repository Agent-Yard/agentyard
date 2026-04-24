package com.lynxus.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.runtime.WorkflowContracts.ToolConnectorType;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
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
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key")
        );

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            ToolConnectorType.BUSINESS_CODE_SECRET_HTTP,
            "Vendor Account",
            IntegrationAccountStatus.ACTIVE,
            Map.of("baseUrl", "https://vendor.example"),
            Map.of("businessCode", "biz-001", "secretKey", "secret-001")
        ));
        var runtimeCredential = service.runtimeCredential(created.id());

        assertTrue(created.credentialConfigured());
        assertFalse(saved.get().credentialCiphertext().contains("secret-001"));
        assertEquals("biz-001", runtimeCredential.credential().get("businessCode"));
        assertEquals("secret-001", runtimeCredential.credential().get("secretKey"));
    }

    @Test
    void shouldPreserveCredentialWhenUpdateCredentialIsNull() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key")
        );

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            ToolConnectorType.BUSINESS_CODE_SECRET_HTTP,
            "Vendor Account",
            IntegrationAccountStatus.ACTIVE,
            Map.of(),
            Map.of("businessCode", "biz-001", "secretKey", "secret-001")
        ));
        String originalCiphertext = saved.get().credentialCiphertext();
        service.updateAccount(created.id(), new UpdateIntegrationAccountRequest("Renamed", IntegrationAccountStatus.ACTIVE, Map.of("baseUrl", "https://vendor.example"), null));

        assertEquals(originalCiphertext, saved.get().credentialCiphertext());
        assertEquals("Renamed", saved.get().name());
        assertEquals("https://vendor.example", saved.get().config().get("baseUrl"));
    }
}
