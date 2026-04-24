package com.lynxus.platform.integration;

import com.lynxus.platform.integration.IntegrationCredentialCrypto.EncryptedCredential;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountDto;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.RuntimeIntegrationCredentialDto;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationAccountService {
    private final IntegrationAccountRepository repository;
    private final IntegrationCredentialCrypto credentialCrypto;

    public IntegrationAccountService(IntegrationAccountRepository repository, IntegrationCredentialCrypto credentialCrypto) {
        this.repository = repository;
        this.credentialCrypto = credentialCrypto;
    }

    @Transactional(readOnly = true)
    public List<IntegrationAccountDto> listAccounts() {
        return repository.listAccounts().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationAccountDto getAccount(String accountId) {
        return toDto(requireAccount(accountId));
    }

    @Transactional
    public IntegrationAccountDto createAccount(CreateIntegrationAccountRequest request) {
        if (request == null || request.connectorType() == null) {
            throw new IllegalArgumentException("integrationAccount.connectorType is required");
        }
        EncryptedCredential encryptedCredential = credentialCrypto.encrypt(request.credential());
        Instant now = Instant.now();
        StoredIntegrationAccount account = new StoredIntegrationAccount(
            "integration-account-" + UUID.randomUUID(),
            request.connectorType(),
            requireText(request.name(), "integrationAccount.name"),
            request.status() == null ? IntegrationAccountStatus.ACTIVE : request.status(),
            request.config() == null ? Map.of() : request.config(),
            encryptedCredential.ciphertext(),
            encryptedCredential.fingerprint(),
            now,
            now
        );
        repository.saveAccount(account);
        return toDto(account);
    }

    @Transactional
    public IntegrationAccountDto updateAccount(String accountId, UpdateIntegrationAccountRequest request) {
        StoredIntegrationAccount existing = requireAccount(accountId);
        EncryptedCredential encryptedCredential = request == null || request.credential() == null
            ? new EncryptedCredential(existing.credentialCiphertext(), existing.credentialFingerprint())
            : credentialCrypto.encrypt(request.credential());
        StoredIntegrationAccount updated = new StoredIntegrationAccount(
            existing.id(),
            existing.connectorType(),
            request == null || request.name() == null ? existing.name() : requireText(request.name(), "integrationAccount.name"),
            request == null || request.status() == null ? existing.status() : request.status(),
            request == null || request.config() == null ? existing.config() : request.config(),
            encryptedCredential.ciphertext(),
            encryptedCredential.fingerprint(),
            existing.createdAt(),
            Instant.now()
        );
        repository.saveAccount(updated);
        return toDto(updated);
    }

    @Transactional(readOnly = true)
    public RuntimeIntegrationCredentialDto runtimeCredential(String accountId) {
        StoredIntegrationAccount account = requireAccount(accountId);
        return new RuntimeIntegrationCredentialDto(
            account.id(),
            account.connectorType(),
            account.status(),
            account.config(),
            credentialCrypto.decrypt(account.credentialCiphertext())
        );
    }

    private StoredIntegrationAccount requireAccount(String accountId) {
        String normalizedAccountId = requireText(accountId, "integrationAccount.id");
        return repository.findAccount(normalizedAccountId)
            .orElseThrow(() -> new NoSuchElementException("integration account not found: " + normalizedAccountId));
    }

    private IntegrationAccountDto toDto(StoredIntegrationAccount account) {
        return new IntegrationAccountDto(
            account.id(),
            account.connectorType(),
            account.name(),
            account.status(),
            account.config(),
            account.credentialCiphertext() != null && !account.credentialCiphertext().isBlank(),
            account.createdAt(),
            account.updatedAt()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
