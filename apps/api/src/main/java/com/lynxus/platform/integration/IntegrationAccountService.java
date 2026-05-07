package com.lynxus.platform.integration;

import com.lynxus.extension.sdk.validation.JsonSchemaValues;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.CredentialCapabilityMode;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionRegistryNotReadyException;
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.extension.ExtensionDefinitionService.InternalCredentialRoutingFacts;
import com.lynxus.platform.integration.IntegrationCredentialCrypto.EncryptedCredential;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountCredentialRequest;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityBlock;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityDecision;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityRisk;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountRuntimeSnapshot;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountDto;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleRequest;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleResponse;
import com.lynxus.platform.integration.IntegrationDtos.RotateIntegrationAccountCredentialRequest;
import com.lynxus.platform.integration.IntegrationDtos.RuntimeIntegrationCredentialDto;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountStatusRequest;
import com.lynxus.platform.shared.ApiProblemException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationAccountService {
    private static final String ACCOUNT_CONFIG_SCHEMA_ID = "https://lynxus.local/schemas/integration-account-config.schema.json";
    private static final int ACCOUNT_NAME_MAX_LENGTH = 128;

    private final IntegrationAccountRepository repository;
    private final IntegrationCredentialCrypto credentialCrypto;
    private final ExtensionDefinitionService definitionService;
    private final IntegrationCredentialLifecycleClient credentialLifecycleClient;
    private final IntegrationAccountChangeNotifier changeNotifier;

    @Autowired
    public IntegrationAccountService(
        IntegrationAccountRepository repository,
        IntegrationCredentialCrypto credentialCrypto,
        ExtensionDefinitionService definitionService,
        IntegrationCredentialLifecycleClient credentialLifecycleClient,
        IntegrationAccountChangeNotifier changeNotifier
    ) {
        this.repository = repository;
        this.credentialCrypto = credentialCrypto;
        this.definitionService = definitionService;
        this.credentialLifecycleClient = credentialLifecycleClient;
        this.changeNotifier = changeNotifier == null ? IntegrationAccountChangeNotifier.noop() : changeNotifier;
    }

    IntegrationAccountService(
        IntegrationAccountRepository repository,
        IntegrationCredentialCrypto credentialCrypto,
        ExtensionDefinitionService definitionService
    ) {
        this(repository, credentialCrypto, definitionService, (baseUrl, path, request, traceId, requestId) -> {
            throw remoteFailure();
        }, IntegrationAccountChangeNotifier.noop());
    }

    public IntegrationAccountService(
        IntegrationAccountRepository repository,
        IntegrationCredentialCrypto credentialCrypto,
        ExtensionDefinitionService definitionService,
        IntegrationCredentialLifecycleClient credentialLifecycleClient
    ) {
        this(repository, credentialCrypto, definitionService, credentialLifecycleClient, IntegrationAccountChangeNotifier.noop());
    }

    @Transactional(readOnly = true)
    public List<IntegrationAccountDto> listAccounts() {
        return repository.listAccounts().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationAccountDto getAccount(String accountId) {
        return toDto(requireAccount(accountId));
    }

    @Transactional(readOnly = true)
    public IntegrationAccountAvailabilityDecision evaluateAccountAvailability(
        String accountId,
        IntegrationAccountSubjectType expectedSubjectType,
        String expectedSubjectId
    ) {
        if (expectedSubjectType == null) {
            throw new IllegalArgumentException("integrationAccount.expectedSubjectType is required");
        }
        String normalizedExpectedSubjectId = requireText(
            expectedSubjectId,
            "integrationAccount.expectedSubjectId",
            128
        );
        return availabilityDecision(requireAccount(accountId), expectedSubjectType, normalizedExpectedSubjectId);
    }

    @Transactional(readOnly = true)
    public IntegrationAccountAvailabilityDecision requireAccountAvailability(
        String accountId,
        IntegrationAccountSubjectType expectedSubjectType,
        String expectedSubjectId
    ) {
        IntegrationAccountAvailabilityDecision decision = evaluateAccountAvailability(
            accountId,
            expectedSubjectType,
            expectedSubjectId
        );
        if (!decision.available()) {
            throw accountAvailabilityBlocked(decision);
        }
        return decision;
    }

    @Transactional(readOnly = true)
    public IntegrationAccountRuntimeSnapshot requireRuntimeAccountSnapshot(
        String accountId,
        IntegrationAccountSubjectType expectedSubjectType,
        String expectedSubjectId
    ) {
        if (expectedSubjectType == null) {
            throw new IllegalArgumentException("integrationAccount.expectedSubjectType is required");
        }
        String normalizedExpectedSubjectId = requireText(
            expectedSubjectId,
            "integrationAccount.expectedSubjectId",
            128
        );
        StoredIntegrationAccount account = requireAccount(accountId);
        IntegrationAccountAvailabilityDecision decision = availabilityDecision(
            account,
            expectedSubjectType,
            normalizedExpectedSubjectId
        );
        if (!decision.available()) {
            throw accountAvailabilityBlocked(decision);
        }
        return new IntegrationAccountRuntimeSnapshot(
            account.id(),
            hasText(account.externalSecretRef()) ? account.externalSecretRef() : null,
            account.name(),
            account.status(),
            account.credentialStatus(),
            hasText(account.externalSecretRef()) || hasText(account.credentialCiphertext()),
            decision.risks()
        );
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public IntegrationAccountDto createAccount(CreateIntegrationAccountRequest request) {
        if (request == null || request.subjectType() == null) {
            throw new IllegalArgumentException("integrationAccount.subjectType is required");
        }
        String subjectId = requireText(request.subjectId(), "integrationAccount.subjectId", 128);
        String name = requireText(request.name(), "integrationAccount.name", ACCOUNT_NAME_MAX_LENGTH);
        Map<String, Object> config = request.config() == null ? Map.of() : request.config();
        validateSubjectConfig(request.subjectType(), subjectId, config);
        InternalCredentialRoutingFacts credentialFacts = credentialRoutingFacts(request.subjectType(), subjectId);

        Instant now = Instant.now();
        StoredIntegrationAccount account = new StoredIntegrationAccount(
            "integration-account-" + UUID.randomUUID(),
            request.subjectType(),
            subjectId,
            name,
            request.status() == null ? IntegrationAccountStatus.ENABLED : request.status(),
            config,
            null,
            null,
            null,
            IntegrationAccountCredentialStatus.NOT_CONFIGURED,
            Map.of(),
            now,
            now
        );
        if (request.credential() != null) {
            ensureCredentialModeSupported(credentialFacts);
            validateCredential(credentialFacts.credentialSchema(), requireCredentialObject(request.credential()));
        }
        saveAndPublish(account, "ACCOUNT_CREATED");
        if (request.credential() != null) {
            try {
                account = createCredential(account, credentialFacts, request.credential());
            } catch (ApiProblemException error) {
                if ("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED".equals(error.code())) {
                    saveAndPublish(withCredentialState(
                        account,
                        null,
                        null,
                        null,
                        IntegrationAccountCredentialStatus.VALIDATION_FAILED
                    ), "CREDENTIAL_CREATE_FAILED");
                }
                throw error;
            }
            saveAndPublish(account, "CREDENTIAL_CREATED");
        }
        return toDto(account);
    }

    @Transactional
    public IntegrationAccountDto updateAccount(String accountId, UpdateIntegrationAccountRequest request) {
        StoredIntegrationAccount existing = requireAccount(accountId);
        Map<String, Object> config = request == null || request.config() == null ? existing.config() : request.config();
        validateSubjectConfig(existing.subjectType(), existing.subjectId(), config);

        StoredIntegrationAccount updated = new StoredIntegrationAccount(
            existing.id(),
            existing.subjectType(),
            existing.subjectId(),
            request == null || request.name() == null
                ? existing.name()
                : requireText(request.name(), "integrationAccount.name", ACCOUNT_NAME_MAX_LENGTH),
            request == null || request.status() == null ? existing.status() : request.status(),
            config,
            existing.externalSecretRef(),
            existing.credentialCiphertext(),
            existing.credentialFingerprint(),
            existing.credentialStatus(),
            existing.metadata(),
            existing.createdAt(),
            Instant.now()
        );
        saveAndPublish(updated, "ACCOUNT_UPDATED");
        return toDto(updated);
    }

    @Transactional
    public IntegrationAccountDto updateAccountStatus(String accountId, UpdateIntegrationAccountStatusRequest request) {
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("integrationAccount.status is required");
        }
        return saveStatus(accountId, request.status());
    }

    @Transactional
    public IntegrationAccountDto archiveAccount(String accountId) {
        return saveStatus(accountId, IntegrationAccountStatus.ARCHIVED);
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public IntegrationAccountDto createCredential(String accountId, CreateIntegrationAccountCredentialRequest request) {
        return withCredentialLock(accountId, normalizedAccountId -> {
            StoredIntegrationAccount account = requireAccount(normalizedAccountId);
            StoredIntegrationAccount updated;
            try {
                updated = createCredential(
                    account,
                    credentialRoutingFacts(account.subjectType(), account.subjectId()),
                    request == null ? null : request.credential()
                );
            } catch (ApiProblemException error) {
                if ("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED".equals(error.code())) {
                    saveAndPublish(withCredentialState(
                        account,
                        null,
                        null,
                        null,
                        IntegrationAccountCredentialStatus.VALIDATION_FAILED
                    ), "CREDENTIAL_CREATE_FAILED");
                }
                throw error;
            }
            saveAndPublish(updated, "CREDENTIAL_CREATED");
            return toDto(updated);
        });
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public IntegrationAccountDto rotateCredential(String accountId, RotateIntegrationAccountCredentialRequest request) {
        return withCredentialLock(accountId, normalizedAccountId -> {
            StoredIntegrationAccount account = requireAccount(normalizedAccountId);
            InternalCredentialRoutingFacts facts = credentialRoutingFacts(account.subjectType(), account.subjectId());
            ensureCredentialModeSupported(facts);
            ensureCredentialUsableForRotateOrValidate(account, facts);
            Map<String, Object> credential = requireCredentialObject(request == null ? null : request.credential());
            validateCredential(facts.credentialSchema(), credential);

            StoredIntegrationAccount updated;
            if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE) {
                EncryptedCredential encrypted = credentialCrypto.encrypt(credential);
                updated = withCredentialState(account, null, encrypted.ciphertext(), encrypted.fingerprint(), IntegrationAccountCredentialStatus.ACTIVE);
            } else {
                try {
                    RemoteCredentialLifecycleResponse response = invokeRemoteCredential(
                        facts,
                        account,
                        credential,
                        account.externalSecretRef(),
                        facts.rotateCredentialPath()
                    );
                    if (response.credentialStatus() != IntegrationAccountCredentialStatus.ACTIVE
                        || !account.externalSecretRef().equals(response.externalSecretRef())) {
                        throw remoteFailure();
                    }
                    updated = withCredentialState(account, account.externalSecretRef(), null, null, IntegrationAccountCredentialStatus.ACTIVE);
                } catch (ApiProblemException error) {
                    if ("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED".equals(error.code())) {
                        saveAndPublish(withCredentialState(
                            account,
                            account.externalSecretRef(),
                            account.credentialCiphertext(),
                            account.credentialFingerprint(),
                            IntegrationAccountCredentialStatus.ROTATION_REQUIRED
                        ), "CREDENTIAL_ROTATE_FAILED");
                    }
                    throw error;
                }
            }
            saveAndPublish(updated, "CREDENTIAL_ROTATED");
            return toDto(updated);
        });
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public IntegrationAccountDto validateCredential(String accountId) {
        return withCredentialLock(accountId, normalizedAccountId -> {
            StoredIntegrationAccount account = requireAccount(normalizedAccountId);
            InternalCredentialRoutingFacts facts = credentialRoutingFacts(account.subjectType(), account.subjectId());
            ensureCredentialModeSupported(facts);
            ensureCredentialUsableForRotateOrValidate(account, facts);

            StoredIntegrationAccount updated;
            if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE) {
                updated = validateLocalCredential(account, facts);
            } else {
                ensureRemoteValidateSupported(facts);
                RemoteCredentialLifecycleResponse response = invokeRemoteCredential(
                    facts,
                    account,
                    null,
                    account.externalSecretRef(),
                    facts.validateCredentialPath()
                );
                if (response.externalSecretRef() != null || !isValidRemoteValidateStatus(response.credentialStatus())) {
                    throw remoteFailure();
                }
                updated = withCredentialState(account, account.externalSecretRef(), null, null, response.credentialStatus());
            }
            saveAndPublish(updated, "CREDENTIAL_VALIDATED");
            return toDto(updated);
        });
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public IntegrationAccountDto revokeCredential(String accountId) {
        return withCredentialLock(accountId, normalizedAccountId -> {
            StoredIntegrationAccount account = requireAccount(normalizedAccountId);
            InternalCredentialRoutingFacts facts = credentialRoutingFacts(account.subjectType(), account.subjectId());
            ensureCredentialModeSupported(facts);
            ensureCredentialUsableForRevoke(account, facts);

            StoredIntegrationAccount updated;
            if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE) {
                updated = withCredentialState(account, null, null, null, IntegrationAccountCredentialStatus.REVOKED);
            } else {
                try {
                    RemoteCredentialLifecycleResponse response = invokeRemoteCredential(
                        facts,
                        account,
                        null,
                        account.externalSecretRef(),
                        facts.revokeCredentialPath()
                    );
                    if (response.externalSecretRef() != null || response.credentialStatus() != IntegrationAccountCredentialStatus.REVOKED) {
                        throw remoteFailure();
                    }
                    updated = withCredentialState(account, null, null, null, IntegrationAccountCredentialStatus.REVOKED);
                } catch (ApiProblemException error) {
                    if ("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED".equals(error.code())) {
                        saveAndPublish(withCredentialState(
                            account,
                            account.externalSecretRef(),
                            account.credentialCiphertext(),
                            account.credentialFingerprint(),
                            IntegrationAccountCredentialStatus.REVOKE_FAILED
                        ), "CREDENTIAL_REVOKE_FAILED");
                    }
                    throw error;
                }
            }
            saveAndPublish(updated, "CREDENTIAL_REVOKED");
            return toDto(updated);
        });
    }

    @Transactional(readOnly = true)
    public RuntimeIntegrationCredentialDto runtimeCredential(String accountId) {
        StoredIntegrationAccount account = requireAccount(accountId);
        return new RuntimeIntegrationCredentialDto(
            account.id(),
            account.subjectType(),
            account.subjectId(),
            account.status(),
            account.config(),
            hasText(account.externalSecretRef()) ? Map.of() : credentialCrypto.decrypt(account.credentialCiphertext())
        );
    }

    private StoredIntegrationAccount createCredential(
        StoredIntegrationAccount account,
        InternalCredentialRoutingFacts facts,
        Object rawCredential
    ) {
        ensureCredentialModeSupported(facts);
        if ((account.credentialStatus() != IntegrationAccountCredentialStatus.NOT_CONFIGURED
                && account.credentialStatus() != IntegrationAccountCredentialStatus.VALIDATION_FAILED)
            || hasText(account.externalSecretRef())
            || hasText(account.credentialCiphertext())) {
            throw credentialStateInvalid();
        }
        Map<String, Object> credential = requireCredentialObject(rawCredential);
        validateCredential(facts.credentialSchema(), credential);

        if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE) {
            EncryptedCredential encrypted = credentialCrypto.encrypt(credential);
            return withCredentialState(account, null, encrypted.ciphertext(), encrypted.fingerprint(), IntegrationAccountCredentialStatus.ACTIVE);
        }

        RemoteCredentialLifecycleResponse response = invokeRemoteCredential(
            facts,
            account,
            credential,
            null,
            facts.createCredentialPath()
        );
        if (response.credentialStatus() != IntegrationAccountCredentialStatus.ACTIVE || !hasText(response.externalSecretRef())) {
            throw remoteFailure();
        }
        return withCredentialState(account, response.externalSecretRef(), null, null, IntegrationAccountCredentialStatus.ACTIVE);
    }

    private StoredIntegrationAccount validateLocalCredential(StoredIntegrationAccount account, InternalCredentialRoutingFacts facts) {
        IntegrationAccountCredentialStatus status;
        try {
            Map<String, Object> credential = credentialCrypto.decrypt(account.credentialCiphertext());
            validateCredential(facts.credentialSchema(), credential);
            status = IntegrationAccountCredentialStatus.ACTIVE;
        } catch (RuntimeException error) {
            status = IntegrationAccountCredentialStatus.VALIDATION_FAILED;
        }
        return withCredentialState(account, null, account.credentialCiphertext(), account.credentialFingerprint(), status);
    }

    private RemoteCredentialLifecycleResponse invokeRemoteCredential(
        InternalCredentialRoutingFacts facts,
        StoredIntegrationAccount account,
        Map<String, Object> credential,
        String externalSecretRef,
        String path
    ) {
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        String traceparent = "00-" + traceId + "-" + spanId + "-01";
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", facts.descriptorType());
        descriptor.put("id", facts.descriptorId());

        Map<String, Object> accountBody = new LinkedHashMap<>();
        accountBody.put("config", account.config());
        if (hasText(externalSecretRef)) {
            accountBody.put("externalSecretRef", externalSecretRef);
        }

        return credentialLifecycleClient.invoke(
            facts.baseUrl(),
            path,
            new RemoteCredentialLifecycleRequest(
                descriptor,
                accountBody,
                credential,
                Map.of("traceparent", traceparent)
            ),
            traceId,
            requestId
        );
    }

    private Map<String, Object> requireCredentialObject(Object rawCredential) {
        if (!(rawCredential instanceof Map<?, ?> rawMap)) {
            throw credentialInvalid();
        }
        Map<String, Object> credential = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw credentialInvalid();
            }
            credential.put(key, entry.getValue());
        }
        return Map.copyOf(credential);
    }

    private void validateCredential(Map<String, Object> schema, Map<String, Object> credential) {
        validateObjectSchema(schema, credential, true);
    }

    private void validateConfig(Map<String, Object> schema, Map<String, Object> config) {
        validateObjectSchema(schema, config, false);
    }

    private void validateObjectSchema(Map<String, Object> schema, Map<String, Object> value, boolean credential) {
        try {
            JsonSchemaValues.validate(schema, value, ACCOUNT_CONFIG_SCHEMA_ID);
        } catch (ApiProblemException error) {
            throw error;
        } catch (Exception error) {
            throw credential ? credentialInvalid() : configInvalid();
        }
    }

    private <T> T withCredentialLock(String accountId, Function<String, T> operation) {
        String normalizedAccountId = requireText(accountId, "integrationAccount.id", 128);
        repository.acquireCredentialLifecycleLock(normalizedAccountId);
        return operation.apply(normalizedAccountId);
    }

    private StoredIntegrationAccount requireAccount(String accountId) {
        String normalizedAccountId = requireText(accountId, "integrationAccount.id", 128);
        return repository.findAccount(normalizedAccountId)
            .orElseThrow(() -> new NoSuchElementException("integration account not found: " + normalizedAccountId));
    }

    private void saveAndPublish(StoredIntegrationAccount account, String reason) {
        repository.saveAccount(account);
        changeNotifier.accountChanged(account, reason);
    }

    private IntegrationAccountDto toDto(StoredIntegrationAccount account) {
        return new IntegrationAccountDto(
            account.id(),
            account.subjectType(),
            account.subjectId(),
            account.name(),
            account.status(),
            account.config(),
            hasText(account.externalSecretRef()),
            hasText(account.externalSecretRef()) || hasText(account.credentialCiphertext()),
            account.credentialStatus(),
            account.createdAt(),
            account.updatedAt()
        );
    }

    private IntegrationAccountAvailabilityDecision availabilityDecision(
        StoredIntegrationAccount account,
        IntegrationAccountSubjectType expectedSubjectType,
        String expectedSubjectId
    ) {
        IntegrationAccountAvailabilityBlock hardBlock = null;
        if (account.subjectType() != expectedSubjectType || !account.subjectId().equals(expectedSubjectId)) {
            hardBlock = IntegrationAccountAvailabilityBlock.SUBJECT_MISMATCH;
        } else if (account.status() != IntegrationAccountStatus.ENABLED) {
            hardBlock = IntegrationAccountAvailabilityBlock.ACCOUNT_STATUS_NOT_ENABLED;
        } else if (account.credentialStatus() == IntegrationAccountCredentialStatus.REVOKE_FAILED) {
            hardBlock = IntegrationAccountAvailabilityBlock.CREDENTIAL_REVOKE_FAILED;
        } else if (account.credentialStatus() == IntegrationAccountCredentialStatus.REVOKED) {
            hardBlock = IntegrationAccountAvailabilityBlock.CREDENTIAL_REVOKED;
        }

        return new IntegrationAccountAvailabilityDecision(
            account.id(),
            account.subjectType(),
            account.subjectId(),
            account.status(),
            account.credentialStatus(),
            hardBlock,
            availabilityRisks(account.credentialStatus())
        );
    }

    private static List<IntegrationAccountAvailabilityRisk> availabilityRisks(IntegrationAccountCredentialStatus credentialStatus) {
        return switch (credentialStatus) {
            case NOT_CONFIGURED -> List.of(IntegrationAccountAvailabilityRisk.CREDENTIAL_NOT_CONFIGURED);
            case VALIDATION_FAILED -> List.of(IntegrationAccountAvailabilityRisk.CREDENTIAL_VALIDATION_FAILED);
            case ROTATION_REQUIRED -> List.of(IntegrationAccountAvailabilityRisk.CREDENTIAL_ROTATION_REQUIRED);
            case ACTIVE, REVOKE_FAILED, REVOKED -> List.of();
        };
    }

    private IntegrationAccountDto saveStatus(String accountId, IntegrationAccountStatus status) {
        StoredIntegrationAccount existing = requireAccount(accountId);
        StoredIntegrationAccount updated = new StoredIntegrationAccount(
            existing.id(),
            existing.subjectType(),
            existing.subjectId(),
            existing.name(),
            status,
            existing.config(),
            existing.externalSecretRef(),
            existing.credentialCiphertext(),
            existing.credentialFingerprint(),
            existing.credentialStatus(),
            existing.metadata(),
            existing.createdAt(),
            Instant.now()
        );
        saveAndPublish(updated, "ACCOUNT_STATUS_UPDATED");
        return toDto(updated);
    }

    private void validateSubjectConfig(IntegrationAccountSubjectType subjectType, String subjectId, Map<String, Object> config) {
        ExtensionDefinitionRegistry registry = definitionService.loadRegistry();
        if (!registry.ready()) {
            throw new ExtensionDefinitionRegistryNotReadyException();
        }

        Map<String, Object> accountConfigSchema = switch (subjectType) {
            case TOOL_CONNECTOR -> registry.toolConnectors().stream()
                .filter(definition -> subjectId.equals(definition.connectorType()))
                .findFirst()
                .map(ToolConnectorDefinition::accountConfigSchema)
                .orElseThrow(() -> subjectNotFound(subjectType, subjectId));
            case CHANNEL_PROVIDER -> registry.channelProviders().stream()
                .filter(definition -> subjectId.equals(definition.providerType()))
                .findFirst()
                .map(ChannelProviderDefinition::accountConfigSchema)
                .orElseThrow(() -> subjectNotFound(subjectType, subjectId));
        };
        validateConfig(accountConfigSchema == null ? Map.of() : accountConfigSchema, config == null ? Map.of() : config);
    }

    private InternalCredentialRoutingFacts credentialRoutingFacts(IntegrationAccountSubjectType subjectType, String subjectId) {
        return definitionService.requireCredentialRoutingFacts(subjectType.name(), subjectId);
    }

    private void ensureCredentialModeSupported(InternalCredentialRoutingFacts facts) {
        if (facts.credentialMode() == null || facts.credentialSchema() == null) {
            throw credentialUnsupported();
        }
    }

    private void ensureCredentialUsableForRotateOrValidate(StoredIntegrationAccount account, InternalCredentialRoutingFacts facts) {
        if (account.credentialStatus() == IntegrationAccountCredentialStatus.REVOKED
            || account.credentialStatus() == IntegrationAccountCredentialStatus.REVOKE_FAILED) {
            throw credentialStateInvalid();
        }
        if (facts.credentialMode() == CredentialCapabilityMode.REMOTE_LIFECYCLE && !hasText(account.externalSecretRef())) {
            throw credentialStateInvalid();
        }
        if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE && !hasText(account.credentialCiphertext())) {
            throw credentialStateInvalid();
        }
    }

    private void ensureCredentialUsableForRevoke(StoredIntegrationAccount account, InternalCredentialRoutingFacts facts) {
        if (account.credentialStatus() == IntegrationAccountCredentialStatus.REVOKED) {
            throw credentialStateInvalid();
        }
        if (facts.credentialMode() == CredentialCapabilityMode.REMOTE_LIFECYCLE && !hasText(account.externalSecretRef())) {
            throw credentialStateInvalid();
        }
        if (facts.credentialMode() == CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE && !hasText(account.credentialCiphertext())) {
            throw credentialStateInvalid();
        }
    }

    private void ensureRemoteValidateSupported(InternalCredentialRoutingFacts facts) {
        if (facts.credentialMode() == CredentialCapabilityMode.REMOTE_LIFECYCLE && !hasText(facts.validateCredentialPath())) {
            throw credentialValidateUnsupported();
        }
    }

    private StoredIntegrationAccount withCredentialState(
        StoredIntegrationAccount account,
        String externalSecretRef,
        String credentialCiphertext,
        String credentialFingerprint,
        IntegrationAccountCredentialStatus credentialStatus
    ) {
        return new StoredIntegrationAccount(
            account.id(),
            account.subjectType(),
            account.subjectId(),
            account.name(),
            account.status(),
            account.config(),
            externalSecretRef,
            credentialCiphertext,
            credentialFingerprint,
            credentialStatus,
            Map.of(),
            account.createdAt(),
            Instant.now()
        );
    }

    private static boolean isValidRemoteValidateStatus(IntegrationAccountCredentialStatus status) {
        return status == IntegrationAccountCredentialStatus.ACTIVE
            || status == IntegrationAccountCredentialStatus.VALIDATION_FAILED
            || status == IntegrationAccountCredentialStatus.ROTATION_REQUIRED;
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder result = new StringBuilder(byteCount * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static ApiProblemException subjectNotFound(IntegrationAccountSubjectType subjectType, String subjectId) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND",
            "INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND: integration account subject does not exist: " + subjectType + "/" + subjectId
        );
    }

    private static ApiProblemException configInvalid() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_CONFIG_INVALID",
            "INTEGRATION_ACCOUNT_CONFIG_INVALID: integration account config does not satisfy accountConfigSchema"
        );
    }

    private static ApiProblemException credentialUnsupported() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_CREDENTIAL_UNSUPPORTED",
            "INTEGRATION_ACCOUNT_CREDENTIAL_UNSUPPORTED: descriptor does not support Core-managed credential lifecycle"
        );
    }

    private static ApiProblemException credentialValidateUnsupported() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_CREDENTIAL_VALIDATE_UNSUPPORTED",
            "INTEGRATION_ACCOUNT_CREDENTIAL_VALIDATE_UNSUPPORTED: descriptor does not support credential validation"
        );
    }

    private static ApiProblemException credentialInvalid() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_CREDENTIAL_INVALID",
            "INTEGRATION_ACCOUNT_CREDENTIAL_INVALID: integration account credential does not satisfy credentialSchema"
        );
    }

    private static ApiProblemException credentialStateInvalid() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_CREDENTIAL_STATE_INVALID",
            "INTEGRATION_ACCOUNT_CREDENTIAL_STATE_INVALID: credential lifecycle action is not allowed for the current account state"
        );
    }

    private static ApiProblemException remoteFailure() {
        return new ApiProblemException(
            HttpStatus.BAD_GATEWAY,
            "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
            "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
        );
    }

    private static ApiProblemException accountAvailabilityBlocked(IntegrationAccountAvailabilityDecision decision) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED",
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED: integration account cannot be used for runtime snapshot: "
                + decision.accountId() + " " + decision.hardBlock()
        );
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
