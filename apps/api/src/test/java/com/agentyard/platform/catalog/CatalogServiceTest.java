package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.extension.sdk.protocol.AgentYardExtensionHeaders;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType;
import com.agentyard.extension.sdk.registration.ExtensionRegistrationLoader;
import com.agentyard.contracts.runtime.WorkflowContracts.ResourceType;
import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import com.agentyard.platform.auth.CurrentUserResolver;
import com.agentyard.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.agentyard.platform.event.PlatformEventRepository;
import com.agentyard.platform.event.PlatformEventService;
import com.agentyard.platform.extension.ExtensionDefinitionService;
import com.agentyard.platform.extension.ExtensionRegistrationProperties;
import com.agentyard.platform.extension.ExtensionRegistrationService;
import com.agentyard.platform.integration.IntegrationAccountRepository;
import com.agentyard.platform.integration.IntegrationAccountService;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityDecision;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.agentyard.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.agentyard.platform.knowledge.KnowledgeServiceClient;
import com.agentyard.platform.knowledge.KnowledgeWorkflowGateway;
import com.agentyard.platform.shared.ApiProblemException;
import com.agentyard.platform.shared.ConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class CatalogServiceTest {
    @Test
    void modelBlueprintDoesNotPretendToBeAConfiguredModel() {
        var service = new CatalogService();
        var blueprint = service.resourceBlueprints().stream().filter(item -> item.type() == ResourceType.LLM_MODEL).findFirst().orElseThrow();
        assertEquals("", blueprint.defaultConfiguration().llmModel().modelId());
        assertEquals("", blueprint.defaultConfiguration().llmModel().baseUrl());
        assertThrows(IllegalArgumentException.class, () -> service.createResource(new CatalogDtos.CreateResourceRequest("Unconfigured",ResourceType.LLM_MODEL,
            ShareScope.PRIVATE,"PERSON","operator","","",List.of(),new CatalogDtos.CreateResourceVersionRequest("",VersionStatus.PUBLISHED,blueprint.defaultConfiguration()))));
    }

    @Test
    void resolvesIndependentTemplatesAndKeepsExactResourceVersions() {
        var service = new CatalogService();
        var model = model(service, "model-first");
        var template = template(service, model.effectiveVersion().id(), List.of(), List.of());
        var first = template.effectiveVersion();
        assertEquals("model-first", service.resolveRoleTemplateVersion(first.id()).model().modelId());
        var nextModel = service.createResourceVersion(model.id(), new CatalogDtos.CreateResourceVersionRequest("new model", VersionStatus.PUBLISHED,
            new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.LLM_MODEL, null,
                new CatalogDtos.LlmModelConfigDto("OPENAI_COMPATIBLE", "model-second", "http://model.test/v1", "MODEL_TEST_KEY", 0.2, 4096), null)));
        assertEquals(VersionStatus.PUBLISHED, service.listResourceVersions(model.id()).getFirst().status());
        assertEquals("model-first", service.resolveRoleTemplateVersion(first.id()).model().modelId());
        var next = service.createRoleTemplateVersion(template.id(), new CatalogDtos.CreateRoleTemplateVersionRequest(VersionStatus.PUBLISHED,
            roleConfig(nextModel.id(), List.of(), List.of())));
        assertEquals("model-second", service.resolveRoleTemplateVersion(next.id()).model().modelId());
        assertEquals("model-first", service.resolveRoleTemplateVersion(first.id()).model().modelId());
        assertThrows(IllegalStateException.class, () -> service.updateResourceVersion(model.id(), model.effectiveVersion().id(),
            new CatalogDtos.UpdateResourceVersionRequest("tamper", VersionStatus.DRAFT, model.effectiveVersion().configuration())));
        assertThrows(IllegalStateException.class, () -> service.deleteRoleTemplate(template.id()));
    }

    @Test
    void entryPermissionsRemainSeparateFromRoleConfiguration() {
        var service = new CatalogService();
        var model = model(service, "reception");
        var template = template(service, model.effectiveVersion().id(), List.of(), List.of());
        var request = new CatalogDtos.EntryRequest("Reception", "", "PERSON", "operator", ShareScope.PRIVATE,
            template.effectiveVersion().id(), true, List.of(template.id()), List.of(new com.agentyard.contracts.agent.AgentContracts.GrantRequest(
                "task.create", com.agentyard.contracts.agent.AgentContracts.ScopeKind.PLATFORM, "platform", false, null)));
        var entry = service.createEntry(request);
        assertEquals(1, entry.capabilityGrants().size());
        assertEquals(List.of(template.id()), entry.allowedTemplateIds());
        assertEquals(template.effectiveVersion(), service.getRoleTemplateVersion(template.effectiveVersion().id()));
        assertEquals(1, service.summary().roleTemplates().size());
        assertEquals(1, service.summary().entries().size());
    }

    @Test
    void rejectsUnpublishedAndWrongTypeReferencesBeforeInstantiation() {
        var service = new CatalogService();
        var draft = service.createRoleTemplate(new CatalogDtos.CreateRoleTemplateRequest("Draft", "", "PERSON", "operator",
            new CatalogDtos.CreateRoleTemplateVersionRequest(VersionStatus.DRAFT, roleConfig(null, List.of(), List.of()))));
        assertThrows(IllegalStateException.class, () -> service.resolveRoleTemplateVersion(draft.latestVersion().id()));
        assertThrows(IllegalArgumentException.class, () -> service.publishRoleTemplateVersion(draft.id(), draft.latestVersion().id()));
        var model = model(service, "test");
        assertThrows(IllegalArgumentException.class, () -> template(service, model.effectiveVersion().id(), List.of(model.effectiveVersion().id()), List.of()));
    }

    @Test
    void independentPlaybooksAreVersionedAndResolvedByVersionId() {
        var service = new CatalogService();
        var definition = new CatalogDtos.PlaybookDefinitionDto("a wait", "{}", "{}", null, true, false, "wait",
            List.of(new CatalogDtos.PlaybookNodeDto("wait", "Need input", PlaybookNodeType.HUMAN_TASK, "", null, null, null, null, Map.of(),
                new CatalogDtos.PlaybookNodeLayoutDto(0, 0))), List.of());
        var playbook = service.createPlaybook(new CatalogDtos.CreatePlaybookRequest("Collect input", "", "PERSON", "operator",
            new CatalogDtos.CreatePlaybookVersionRequest(VersionStatus.PUBLISHED, definition)));
        var model = model(service, "test");
        var template = template(service, model.effectiveVersion().id(), List.of(), List.of(playbook.effectiveVersion().id()));
        assertEquals(playbook.effectiveVersion().id(), service.resolveRoleTemplateVersion(template.effectiveVersion().id()).playbooks().getFirst().playbookId());
        assertThrows(IllegalStateException.class, () -> service.deletePlaybook(playbook.id()));
    }

    @Test
    void runtimeMaterializesCredentialsAndRevalidatesAccountAvailability() {
        var accounts = new RecordingIntegrationAccountService();
        var service = catalogServiceWithToolConnectors(List.of(crmToolConnectorDescriptor()), accounts,
            new FixedIntegrationAccountRepository(storedAccount("account", "enterprise.acme.crm", "vault://catalog-test")));
        var model = model(service, "test");
        var tool = createToolResource(service, toolConfig("enterprise.acme.crm", "account", Map.of("tenantId", "acme"),
            Map.of("query_customer", Map.of("endpoint", "/customers"))));
        var template = template(service, model.effectiveVersion().id(), List.of(tool.effectiveVersion().id()), List.of());
        assertNull(service.listResourceVersions(tool.id()).getFirst().configuration().tool().connector().accountSnapshot());
        var profile = service.resolveRoleTemplateVersion(template.effectiveVersion().id());
        assertEquals("account", profile.tools().getFirst().connector().accountSnapshot().accountId());
        assertEquals("vault://catalog-test", profile.tools().getFirst().connector().accountSnapshot().externalSecretRef());
        accounts.error = new ApiProblemException(HttpStatus.FORBIDDEN,"ACCOUNT_REVOKED","Account is revoked");
        assertThrows(ApiProblemException.class, () -> service.resolveRoleTemplateVersion(template.effectiveVersion().id()));
    }

    static CatalogDtos.ResourceDto model(CatalogService service, String modelId) {
        return service.createResource(new CatalogDtos.CreateResourceRequest("Model", ResourceType.LLM_MODEL, ShareScope.PRIVATE,
            "PERSON", "operator", "", "", List.of(), new CatalogDtos.CreateResourceVersionRequest("initial", VersionStatus.PUBLISHED,
                new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.LLM_MODEL, null,
                    new CatalogDtos.LlmModelConfigDto("OPENAI_COMPATIBLE", modelId, "http://model.test/v1", "MODEL_TEST_KEY", 0.2, 4096), null))));
    }
    static CatalogDtos.RoleTemplateDto template(CatalogService service, String modelId, List<String> tools, List<String> playbooks) {
        return service.createRoleTemplate(new CatalogDtos.CreateRoleTemplateRequest("Research", "", "PERSON", "operator",
            new CatalogDtos.CreateRoleTemplateVersionRequest(VersionStatus.PUBLISHED, roleConfig(modelId, tools, playbooks))));
    }
    static CatalogDtos.RoleConfigurationDto roleConfig(String modelId, List<String> tools, List<String> playbooks) {
        return new CatalogDtos.RoleConfigurationDto("research", "Research the assigned goal", "Keep source references.", modelId, null, false, null,
            16, List.of(), tools, playbooks);
    }

    @Test
    void shouldNormalizeToolConnectorConfigFromExtensionDefinitionDescriptorId() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );
        CatalogDtos.ResourceDto tool = createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        );

        CatalogDtos.ToolConnectorConfigDto connector = tool.versions().getFirst().configuration().tool().connector();

        assertEquals("enterprise.acme.crm", connector.connectorType());
        assertNull(connector.accountId());
        assertNull(connector.accountSnapshot());
        assertEquals(Map.of("tenantId", "acme"), connector.config());
        assertEquals(Map.of("endpoint", "/customers"), connector.operationMappings().get("query_customer"));
    }

    @Test
    void shouldRejectToolConnectorConfigThatDoesNotMatchCurrentDefinitionSchema() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of(),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));

        assertTrue(error.getMessage().contains("tool connector config does not satisfy connector schema"));
    }

    @Test
    void shouldRequireExplicitOperationMappingForEveryToolOperation() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            new CatalogDtos.ToolConfigDto(
                List.of(
                    new CatalogDtos.ToolOperationDto("query_customer", "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}"),
                    new CatalogDtos.ToolOperationDto("create_ticket", "创建工单", "{\"type\":\"object\"}", "{\"type\":\"object\"}")
                ),
                new CatalogDtos.ToolConnectorConfigDto(
                    "enterprise.acme.crm",
                    null,
                    null,
                    20,
                    "NONE",
                    Map.of("tenantId", "acme"),
                    Map.of("query_customer", Map.of("endpoint", "/customers"))
                )
            )
        ));

        assertTrue(error.getMessage().contains("operationMappings missing operation: create_ticket"));
    }

    @Test
    void shouldRejectSecretLikeMaterialInToolConnectorConfigAndOperationMappings() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException configError = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme", "apiKey", "secret"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));
        IllegalArgumentException mappingError = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers", "externalSecretRef", "vault://secret"))
            )
        ));

        assertTrue(configError.getMessage().contains("secret-like key"));
        assertTrue(mappingError.getMessage().contains("secret-like key"));
    }

    @Test
    void shouldRejectToolResourceWithoutExplicitConnectorConfig() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            new CatalogDtos.ToolConfigDto(
                List.of(new CatalogDtos.ToolOperationDto("query_customer", "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}")),
                null
            )
        ));

        assertEquals("tool connector config is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceWithoutToolConfig() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            null
        ));

        assertEquals("tool config is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceVersionCreateWithNullConfiguration() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
        CatalogDtos.ResourceDto resource = createToolResource(catalogService, simpleHttpToolConfig());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> catalogService.createResourceVersion(
            resource.id(),
            new CatalogDtos.CreateResourceVersionRequest("null config", VersionStatus.DRAFT, null)
        ));

        assertEquals("tool resource version configuration is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceVersionUpdateWithNullConfiguration() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
        CatalogDtos.ResourceDto resource = createDraftToolResource(catalogService, simpleHttpToolConfig());
        CatalogDtos.ResourceVersionDto draftVersion = resource.versions().getFirst();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> catalogService.updateResourceVersion(
            resource.id(),
            draftVersion.id(),
            new CatalogDtos.UpdateResourceVersionRequest("null config", VersionStatus.DRAFT, null)
        ));

        assertEquals("tool resource version configuration is required", error.getMessage());
    }

    @Test
    void shouldFailClearlyWhenToolConnectorDefinitionIsMissing() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.missing",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));

        assertEquals("tool connector definition not found: enterprise.missing", error.getMessage());
    }

    @Test
    void shouldFailClearlyWhenToolConnectorDefinitionsAreUnavailable() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> createToolResource(
            catalogService,
            simpleHttpToolConfig()
        ));

        assertEquals(
            "tool connector definition registry is unavailable; ExtensionDefinitionService is required",
            error.getMessage()
        );
    }

    private static CatalogService catalogServiceWithToolConnectors(
        List<Map<String, Object>> toolConnectorDescriptors,
        IntegrationAccountService integrationAccountService,
        IntegrationAccountRepository integrationAccountRepository
    ) {
        ExtensionDefinitionService definitionService = extensionDefinitionService(toolConnectorDescriptors);
        return new CatalogService(
            new InMemoryCatalogRepository(),
            new com.agentyard.platform.knowledge.InMemoryKnowledgeRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            PlatformEventService.disabled(),
            null,
            definitionService,
            integrationAccountService,
            integrationAccountRepository
        );
    }

    private static CatalogService catalogServiceWithCoreToolConnectors() {
        return catalogServiceWithCoreToolConnectors(PlatformEventService.disabled());
    }

    private static CatalogService catalogServiceWithCoreToolConnectors(PlatformEventService platformEventService) {
        return catalogServiceWithCoreToolConnectors(
            new InMemoryCatalogRepository(),
            new com.agentyard.platform.knowledge.InMemoryKnowledgeRepository(),
            platformEventService,
            null
        );
    }

    private static CatalogService catalogServiceWithCoreToolConnectors(
        CatalogRepository catalogRepository,
        com.agentyard.platform.knowledge.KnowledgeRepository knowledgeRepository,
        PlatformEventService platformEventService,
        com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        return new CatalogService(
            catalogRepository,
            knowledgeRepository,
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            platformEventService,
            invalidationBus,
            extensionDefinitionService(List.of()),
            null,
            null
        );
    }

    private static ExtensionDefinitionService extensionDefinitionService(List<Map<String, Object>> toolConnectorDescriptors) {
        return new ExtensionDefinitionService(
            registrationServiceWithRemoteToolConnector(!toolConnectorDescriptors.isEmpty()),
            (manifestUrl, headers) -> {
                String registrationId = headers.get(AgentYardExtensionHeaders.REGISTRATION_ID);
                if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registrationId)) {
                    return manifest(List.of(channelProviderDescriptor("feishu")), List.of());
                }
                if (ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID.equals(registrationId)) {
                    return manifest(List.of(), coreToolConnectorDescriptors());
                }
                if ("acme-remote".equals(registrationId)) {
                    return manifest(List.of(), toolConnectorDescriptors);
                }
                throw new AssertionError("unexpected registration id: " + registrationId);
            },
            "internal-token"
        );
    }

    private static ExtensionRegistrationService registrationServiceWithRemoteToolConnector(boolean includeRemoteToolConnector) {
        try {
            Path tempFile = Files.createTempFile("agentyard-catalog-tool-connector", ".yaml");
            Files.writeString(tempFile, includeRemoteToolConnector
                ? """
                    agentyard:
                      extensions:
                        services:
                          - registrationId: acme-remote
                            baseUrl: https://remote.example.com/private
                            exposes:
                              toolConnectorTypes:
                                - enterprise.acme.crm
                            auth:
                              type: INTERNAL_TOKEN
                    """
                : """
                    agentyard:
                      extensions:
                        services: []
                    """);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static CatalogDtos.ToolConfigDto toolConfig(
        String connectorType,
        String accountId,
        Map<String, Object> config,
        Map<String, Map<String, Object>> operationMappings
    ) {
        return toolConfig(connectorType, accountId, config, "query_customer", operationMappings);
    }

    private static CatalogDtos.ToolConfigDto toolConfig(
        String connectorType,
        String accountId,
        Map<String, Object> config,
        String operationName,
        Map<String, Map<String, Object>> operationMappings
    ) {
        return new CatalogDtos.ToolConfigDto(
            List.of(new CatalogDtos.ToolOperationDto(operationName, "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}")),
            new CatalogDtos.ToolConnectorConfigDto(
                connectorType,
                accountId,
                null,
                20,
                "NONE",
                config,
                operationMappings
            )
        );
    }

    private static CatalogDtos.ToolConfigDto simpleHttpToolConfig() {
        return simpleHttpToolConfig("query_customer");
    }

    private static CatalogDtos.ToolConfigDto simpleHttpToolConfig(String operationName) {
        return toolConfig(
            "simple-http",
            null,
            Map.of("baseUrl", "https://tools.example.com"),
            operationName,
            Map.of(operationName, Map.of("endpoint", "/customers"))
        );
    }

    private static CatalogDtos.ResourceVersionConfigurationDto toolResourceConfiguration(CatalogDtos.ToolConfigDto toolConfig) {
        return new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, toolConfig, null, null);
    }

    private static List<Map<String, Object>> coreToolConnectorDescriptors() {
        return List.of(
            toolConnectorDescriptor(
                "business-code-secret-http",
                objectSchema(Map.of("baseUrl", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("endpoint", Map.of("type", "string")), List.of())
            ),
            toolConnectorDescriptor(
                "mcp",
                objectSchema(Map.of("connectionUri", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("tool", Map.of("type", "string")), List.of())
            ),
            toolConnectorDescriptor(
                "simple-http",
                objectSchema(Map.of("baseUrl", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("endpoint", Map.of("type", "string")), List.of())
            )
        );
    }

    private static Map<String, Object> crmToolConnectorDescriptor() {
        return toolConnectorDescriptor(
            "enterprise.acme.crm",
            objectSchema(Map.of("tenantId", Map.of("type", "string", "minLength", 1)), List.of("tenantId")),
            objectSchema(Map.of("endpoint", Map.of("type", "string", "minLength", 1)), List.of("endpoint"))
        );
    }

    private static Map<String, Object> toolConnectorDescriptor(
        String connectorType,
        Map<String, Object> configSchema,
        Map<String, Object> operationMappingSchema
    ) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("connectorType", connectorType);
        descriptor.put("title", connectorType);
        descriptor.put("description", connectorType + " description");
        descriptor.put("accountConfigSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", configSchema);
        descriptor.put("configUiSchema", List.of());
        descriptor.put("operationMappingSchema", operationMappingSchema);
        descriptor.put("operationMappingUiSchema", List.of());
        descriptor.put("endpoints", Map.of("invoke", "/tools/" + connectorType.replace(".", "-") + "/invoke"));
        return descriptor;
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", providerType);
        descriptor.put("description", providerType + " description");
        descriptor.put("accountConfigSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("outbound", channelOutboundCapability());
        descriptor.put("endpoints", Map.of());
        return descriptor;
    }

    private static Map<String, Object> channelOutboundCapability() {
        return Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", true,
            "supportsDraftUpdate", true,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        );
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.copyOf(schema);
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

    private static StoredIntegrationAccount storedAccount(String accountId, String subjectId, String externalSecretRef) {
        Instant now = Instant.now();
        return new StoredIntegrationAccount(
            accountId,
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            subjectId,
            "CRM Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            externalSecretRef,
            null,
            null,
            IntegrationAccountCredentialStatus.ACTIVE,
            Map.of(),
            now,
            now
        );
    }

    private static final class RecordingIntegrationAccountService extends IntegrationAccountService {
        String accountId;
        IntegrationAccountSubjectType expectedSubjectType;
        String expectedSubjectId;
        ApiProblemException error;

        RecordingIntegrationAccountService() {
            super(null, null, null, null);
        }

        @Override
        public IntegrationAccountAvailabilityDecision requireAccountAvailability(
            String accountId,
            IntegrationAccountSubjectType expectedSubjectType,
            String expectedSubjectId
        ) {
            this.accountId = accountId;
            this.expectedSubjectType = expectedSubjectType;
            this.expectedSubjectId = expectedSubjectId;
            if (error != null) {
                throw error;
            }
            return new IntegrationAccountAvailabilityDecision(
                accountId,
                expectedSubjectType,
                expectedSubjectId,
                IntegrationAccountStatus.ENABLED,
                IntegrationAccountCredentialStatus.ACTIVE,
                null,
                List.of()
            );
        }
    }

    private static final class FixedIntegrationAccountRepository extends IntegrationAccountRepository {
        private final StoredIntegrationAccount account;

        FixedIntegrationAccountRepository(StoredIntegrationAccount account) {
            super(null, new ObjectMapper());
            this.account = account;
        }

        @Override
        public Optional<StoredIntegrationAccount> findAccount(String accountId) {
            if (account.id().equals(accountId)) {
                return Optional.of(account);
            }
            return Optional.empty();
        }
    }

    private static KnowledgeServiceClient readySnapshotKnowledgeClient() {
        return new KnowledgeServiceClient("http://localhost:8091", "test-internal-token") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-support",
                    "PGVECTOR",
                    "HYBRID",
                    "READY",
                    "READY",
                    100,
                    0,
                    false,
                    1,
                    2,
                    null,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
                );
            }
        };
    }

    private static KnowledgeWorkflowGateway noopKnowledgeWorkflowGateway() {
        return new KnowledgeWorkflowGateway() {
            @Override
            public void startImport(String knowledgeBaseId, String importJobId) {
            }

            @Override
            public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
            }
        };
    }

    private static CatalogDtos.ResourceDto createToolResource(CatalogService service, CatalogDtos.ToolConfigDto config) {
        return toolResource(service, config, VersionStatus.PUBLISHED);
    }
    private static CatalogDtos.ResourceDto createDraftToolResource(CatalogService service, CatalogDtos.ToolConfigDto config) {
        return toolResource(service, config, VersionStatus.DRAFT);
    }
    private static CatalogDtos.ResourceDto toolResource(CatalogService service, CatalogDtos.ToolConfigDto config, VersionStatus status) {
        return service.createResource(new CatalogDtos.CreateResourceRequest("CRM Tool", ResourceType.TOOL, ShareScope.PRIVATE,
            "PERSON", "operator", "", "", List.of(), new CatalogDtos.CreateResourceVersionRequest("tool", status,
                new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, config, null, null))));
    }
}
