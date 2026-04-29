package com.lynxus.platform.integration;

import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionRegistryNotReadyException;
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountDto;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.RuntimeIntegrationCredentialDto;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountStatusRequest;
import com.lynxus.platform.shared.ApiProblemException;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IntegrationAccountService {
    private static final String ACCOUNT_CONFIG_SCHEMA_ID = "https://lynxus.local/schemas/integration-account-config.schema.json";
    private static final int ACCOUNT_NAME_MAX_LENGTH = 128;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationAccountRepository repository;
    private final IntegrationCredentialCrypto credentialCrypto;
    private final ExtensionDefinitionService definitionService;

    public IntegrationAccountService(
        IntegrationAccountRepository repository,
        IntegrationCredentialCrypto credentialCrypto,
        ExtensionDefinitionService definitionService
    ) {
        this.repository = repository;
        this.credentialCrypto = credentialCrypto;
        this.definitionService = definitionService;
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
        if (request == null || request.subjectType() == null) {
            throw new IllegalArgumentException("integrationAccount.subjectType is required");
        }
        String subjectId = requireText(request.subjectId(), "integrationAccount.subjectId", 128);
        String name = requireText(request.name(), "integrationAccount.name", ACCOUNT_NAME_MAX_LENGTH);
        Map<String, Object> config = request.config() == null ? Map.of() : request.config();
        validateSubjectConfig(request.subjectType(), subjectId, config);

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
        repository.saveAccount(account);
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
        repository.saveAccount(updated);
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

    @Transactional(readOnly = true)
    public RuntimeIntegrationCredentialDto runtimeCredential(String accountId) {
        StoredIntegrationAccount account = requireAccount(accountId);
        return new RuntimeIntegrationCredentialDto(
            account.id(),
            account.subjectType(),
            account.subjectId(),
            account.status(),
            account.config(),
            credentialCrypto.decrypt(account.credentialCiphertext())
        );
    }

    private StoredIntegrationAccount requireAccount(String accountId) {
        String normalizedAccountId = requireText(accountId, "integrationAccount.id", 128);
        return repository.findAccount(normalizedAccountId)
            .orElseThrow(() -> new NoSuchElementException("integration account not found: " + normalizedAccountId));
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
        repository.saveAccount(updated);
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

    private void validateConfig(Map<String, Object> schema, Map<String, Object> config) {
        try {
            String schemaJson = JSON.writeValueAsString(schema);
            String configJson = JSON.writeValueAsString(config);
            SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(ACCOUNT_CONFIG_SCHEMA_ID, schemaJson))
            );
            Schema accountConfigSchema = schemaRegistry.getSchema(SchemaLocation.of(ACCOUNT_CONFIG_SCHEMA_ID));
            if (!accountConfigSchema.validate(configJson, InputFormat.JSON).isEmpty()) {
                throw configInvalid();
            }
        } catch (ApiProblemException error) {
            throw error;
        } catch (Exception error) {
            throw configInvalid();
        }
    }

    private static ApiProblemException subjectNotFound(IntegrationAccountSubjectType subjectType, String subjectId) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND",
            "INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND: integration account subject does not exist: " + subjectType + "/" + subjectId
        );
    }

    private static ApiProblemException configInvalid() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INTEGRATION_ACCOUNT_CONFIG_INVALID",
            "INTEGRATION_ACCOUNT_CONFIG_INVALID: integration account config does not satisfy accountConfigSchema"
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
