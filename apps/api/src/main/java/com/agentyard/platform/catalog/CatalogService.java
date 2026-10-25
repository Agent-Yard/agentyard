package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;

import com.agentyard.contracts.runtime.WorkflowContracts.ResourceType;
import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.contracts.agent.AgentContracts.AgentProfile;
import com.agentyard.contracts.agent.AgentContracts.GrantRequest;
import com.agentyard.contracts.runtime.RuntimeContracts.*;
import com.agentyard.extension.sdk.validation.JsonSchemaValues;
import com.agentyard.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.agentyard.platform.event.PlatformEventService;
import com.agentyard.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.agentyard.platform.extension.ExtensionDefinitionService;
import com.agentyard.platform.integration.IntegrationAccountRepository;
import com.agentyard.platform.integration.IntegrationAccountService;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.agentyard.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.agentyard.platform.knowledge.InMemoryKnowledgeRepository;
import com.agentyard.platform.knowledge.KnowledgeRepository;
import com.agentyard.platform.knowledge.KnowledgeService;
import com.agentyard.platform.knowledge.KnowledgeServiceClient;
import com.agentyard.platform.knowledge.KnowledgeWorkflowGateway;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private static final String TOOL_CONNECTOR_CONFIG_SCHEMA_ID = "https://agentyard.local/schemas/tool-connector-config.schema.json";
    private static final int DEFAULT_TOOL_CONNECTOR_TIMEOUT_SECONDS = 15;
    private static final String DEFAULT_TOOL_CONNECTOR_RETRY_POLICY = "NONE";
    private static final Pattern TOOL_OPERATION_FUNCTION_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Set<String> REASONING_EFFORT_VALUES = Set.of("low", "medium", "high", "xhigh");
    private static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of(
        "externalsecretref",
        "password",
        "apikey",
        "accesskey",
        "accesstoken",
        "refreshtoken",
        "privatekey",
        "clientsecret",
        "appsecret",
        "webhooksecret",
        "webhooksigningsecret",
        "signingsecret",
        "secret",
        "secretkey",
        "token",
        "bearertoken"
    );

    private final CatalogRepository repository;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeService knowledgeService;
    private final PlatformEventService platformEventService;
    private final com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus;
    private final ExtensionDefinitionService extensionDefinitionService;
    private final IntegrationAccountService integrationAccountService;
    private final IntegrationAccountRepository integrationAccountRepository;

    public CatalogService() {
        this(
            new InMemoryCatalogRepository(),
            new InMemoryKnowledgeRepository(),
            new KnowledgeServiceClient("http://127.0.0.1:8091", "in-memory-internal-token"),
            new NoOpKnowledgeWorkflowGateway(),
            PlatformEventService.disabled(),
            null,
            null,
            null,
            null
        );
    }

    public CatalogService(CatalogRepository repository, KnowledgeService knowledgeService) {
        this(repository, knowledgeService, PlatformEventService.disabled(), null);
    }

    @Autowired
    public CatalogService(
        CatalogRepository repository,
        KnowledgeService knowledgeService,
        PlatformEventService platformEventService,
        com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus,
        ExtensionDefinitionService extensionDefinitionService,
        IntegrationAccountService integrationAccountService,
        IntegrationAccountRepository integrationAccountRepository
    ) {
        this.repository = repository;
        this.knowledgeRepository = knowledgeService.repository();
        this.knowledgeService = knowledgeService;
        this.platformEventService = platformEventService;
        this.invalidationBus = invalidationBus;
        this.extensionDefinitionService = extensionDefinitionService;
        this.integrationAccountService = integrationAccountService;
        this.integrationAccountRepository = integrationAccountRepository;
    }

    public CatalogService(
        CatalogRepository repository,
        KnowledgeService knowledgeService,
        PlatformEventService platformEventService,
        com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        this(repository, knowledgeService, platformEventService, invalidationBus, null, null, null);
    }

    public CatalogService(CatalogRepository repository, KnowledgeServiceClient knowledgeServiceClient, KnowledgeWorkflowGateway knowledgeWorkflowGateway) {
        this(repository, new InMemoryKnowledgeRepository(), knowledgeServiceClient, knowledgeWorkflowGateway, PlatformEventService.disabled(), null);
    }

    public CatalogService(
        CatalogRepository repository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService
    ) {
        this(repository, new InMemoryKnowledgeRepository(), knowledgeServiceClient, knowledgeWorkflowGateway, platformEventService, null);
    }

    CatalogService(
        CatalogRepository repository,
        com.agentyard.platform.knowledge.KnowledgeRepository knowledgeRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService,
        com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        this(
            repository,
            knowledgeRepository,
            knowledgeServiceClient,
            knowledgeWorkflowGateway,
            platformEventService,
            invalidationBus,
            null,
            null,
            null
        );
    }

    CatalogService(
        CatalogRepository repository,
        com.agentyard.platform.knowledge.KnowledgeRepository knowledgeRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService,
        com.agentyard.platform.shared.redis.RedisInvalidationBus invalidationBus,
        ExtensionDefinitionService extensionDefinitionService,
        IntegrationAccountService integrationAccountService,
        IntegrationAccountRepository integrationAccountRepository
    ) {
        this.repository = repository;
        this.knowledgeRepository = knowledgeRepository;
        this.platformEventService = platformEventService;
        this.invalidationBus = invalidationBus;
        this.extensionDefinitionService = extensionDefinitionService;
        this.integrationAccountService = integrationAccountService;
        this.integrationAccountRepository = integrationAccountRepository;
        this.knowledgeService = new KnowledgeService(
            knowledgeRepository,
            repository,
            knowledgeServiceClient,
            knowledgeWorkflowGateway,
            platformEventService,
            invalidationBus
        );
    }

    public KnowledgeService knowledgeService() {
        return knowledgeService;
    }

    private <T> T withReadState(Function<CatalogRepository, T> action) {
        return repository.inReadTransaction(() -> action.apply(repository));
    }

    private <T> T withReadState(BiFunction<CatalogRepository, KnowledgeRepository, T> action) {
        return repository.inReadTransaction(() ->
            knowledgeRepository.inReadTransaction(() -> action.apply(repository, knowledgeRepository))
        );
    }

    private <T> T withWriteState(Function<CatalogRepository, T> action) {
        T result = repository.inWriteTransaction(() -> action.apply(repository));
        if (invalidationBus != null) {
            invalidationBus.publishCatalogInvalidated(repository.revision());
        }
        return result;
    }

    public CatalogSummaryDto summary() {
        return new CatalogSummaryDto(listRoleTemplates(), listEntries(), listPlaybooks(), listResources(),
            knowledgeService.listKnowledgeBases(), resourceCenter(), resourceBlueprints());
    }

    public List<RoleTemplateDto> listRoleTemplates() {
        return withReadState(state -> state.listRoleTemplates().stream().map(item -> templateView(state, item)).toList());
    }
    public RoleTemplateDto getRoleTemplate(String id) {
        return withReadState(state -> templateView(state, requireTemplate(state, id)));
    }
    public RoleTemplateVersionDto getRoleTemplateVersion(String versionId) {
        return withReadState(state -> state.findRoleTemplateVersion(versionId)
            .orElseThrow(() -> new NoSuchElementException("role template version not found: " + versionId)));
    }
    public RoleTemplateDto createRoleTemplate(CreateRoleTemplateRequest request) {
        return withWriteState(state -> {
            CatalogOwnership.validate(request.ownerType(), request.ownerId());
            var template = new RoleTemplateDto(nextId("role-template"), requireText(request.name(), "template.name"),
                normalizeOptionalText(request.description()), request.ownerType(), request.ownerId(), null, null, List.of());
            state.upsertRoleTemplate(template);
            recordCatalogEvent("ROLE_TEMPLATE_CREATED", PlatformAggregateType.ROLE_TEMPLATE, template.id(), Map.of("name", template.name()));
            if (request.initialVersion() != null) createRoleVersion(state, template, request.initialVersion());
            return templateView(state, template);
        });
    }
    public RoleTemplateDto updateRoleTemplate(String id, UpdateRoleTemplateRequest request) {
        return withWriteState(state -> {
            requireTemplate(state, id);
            CatalogOwnership.validate(request.ownerType(), request.ownerId());
            var template = new RoleTemplateDto(id, requireText(request.name(), "template.name"),
                normalizeOptionalText(request.description()), request.ownerType(), request.ownerId(), null, null, List.of());
            state.upsertRoleTemplate(template);
            recordCatalogEvent("ROLE_TEMPLATE_UPDATED", PlatformAggregateType.ROLE_TEMPLATE, id, Map.of("name", template.name()));
            return templateView(state, template);
        });
    }
    public RoleTemplateDto deleteRoleTemplate(String id) {
        return withWriteState(state -> {
            var template = templateView(state, requireTemplate(state, id));
            if (template.versions().stream().anyMatch(item -> item.status() == VersionStatus.PUBLISHED))
                throw new IllegalStateException("published templates are retained for existing instances");
            if (state.listEntries().stream().anyMatch(e -> e.allowedTemplateIds().contains(id)))
                throw new IllegalStateException("template is allowed by a reception entry");
            state.deleteRoleTemplateVersions(id);
            state.deleteRoleTemplate(id);
            recordCatalogEvent("ROLE_TEMPLATE_DELETED", PlatformAggregateType.ROLE_TEMPLATE, id, Map.of());
            return template;
        });
    }
    public RoleTemplateVersionDto createRoleTemplateVersion(String id, CreateRoleTemplateVersionRequest request) {
        return withWriteState(state -> createRoleVersion(state, requireTemplate(state, id), request));
    }
    private RoleTemplateVersionDto createRoleVersion(CatalogRepository state, RoleTemplateDto template,
        CreateRoleTemplateVersionRequest request) {
        var status = request.status() == null ? VersionStatus.DRAFT : request.status();
        var config = normalizeRoleConfiguration(state, request.configuration(), status == VersionStatus.PUBLISHED);
        var versions = new ArrayList<>(state.listRoleTemplateVersions(template.id()));
        var version = new RoleTemplateVersionDto(nextId("role-version"), template.id(), nextVersion(versions.size()),
            status, Instant.now(), status == VersionStatus.PUBLISHED ? Instant.now() : null, config);
        versions.add(version);
        state.replaceRoleTemplateVersions(template.id(), versions);
        recordVersionEvent(PlatformAggregateType.ROLE_TEMPLATE, template.id(), version.id(), version.version(), version.status());
        return version;
    }
    public RoleTemplateVersionDto publishRoleTemplateVersion(String id, String versionId) {
        return withWriteState(state -> {
            requireTemplate(state, id);
            var version = state.listRoleTemplateVersions(id).stream().filter(v -> v.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("role template version not found: " + versionId));
            if (version.status() == VersionStatus.PUBLISHED) return version;
            normalizeRoleConfiguration(state, version.configuration(), true);
            var published = new RoleTemplateVersionDto(version.id(), id, version.version(), VersionStatus.PUBLISHED,
                version.createdAt(), Instant.now(), version.configuration());
            state.replaceRoleTemplateVersions(id, state.listRoleTemplateVersions(id).stream()
                .map(v -> v.id().equals(versionId) ? published : v).toList());
            recordVersionEvent(PlatformAggregateType.ROLE_TEMPLATE, id, published.id(), published.version(), published.status());
            return published;
        });
    }
    private RoleConfigurationDto normalizeRoleConfiguration(CatalogRepository state, RoleConfigurationDto config, boolean published) {
        if (config == null) throw new IllegalArgumentException("role configuration is required");
        if (published) requireText(config.modelVersionId(), "role.modelVersionId");
        if (hasText(config.modelVersionId())) requireResourceVersion(state, config.modelVersionId(), ResourceType.LLM_MODEL, published);
        if (config.privacyMappingEnabled() && !hasText(config.privacyModelVersionId()))
            throw new IllegalArgumentException("privacyMappingEnabled requires privacyModelVersionId");
        if (hasText(config.privacyModelVersionId())) {
            var privacy = requireResourceVersion(state, config.privacyModelVersionId(), ResourceType.LLM_MODEL, published);
            if (!privacy.configuration().llmModel().privateDeployment())
                throw new IllegalArgumentException("privacy mapping requires a private-deployment model");
        }
        var skills = distinct(config.skillVersionIds());
        var tools = distinct(config.toolVersionIds());
        var playbooks = distinct(config.playbookVersionIds());
        skills.forEach(id -> requireResourceVersion(state, id, ResourceType.SKILL, published));
        Set<String> operationNames = new HashSet<>();
        tools.forEach(id -> {
            var resource = requireResourceVersion(state, id, ResourceType.TOOL, published);
            resource.configuration().tool().operations().forEach(operation -> {
                if (!operationNames.add(operation.name()))
                    throw new IllegalArgumentException("duplicate tool operation in role: " + operation.name());
            });
        });
        playbooks.forEach(id -> {
            var version = requirePlaybookVersion(state, id, published);
            if (published) version.definition().nodes().stream()
                .filter(node -> node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.TOOL_TASK)
                .forEach(node -> {
                    boolean available = tools.stream().map(toolId -> requireResourceVersion(state, toolId, ResourceType.TOOL, true))
                        .anyMatch(tool -> tool.resourceId().equals(node.toolId()) && tool.configuration().tool().operations().stream()
                            .anyMatch(operation -> operation.name().equals(node.toolOperation())));
                    if (!available) throw new IllegalArgumentException("playbook tool node requires a pinned role tool version and operation: " + node.nodeKey());
                });
        });
        if (hasText(config.knowledgeReleaseId())) requireKnowledgeRelease(config.knowledgeReleaseId(), published);
        if (config.memoryWindowSize() < 1 || config.memoryWindowSize() > 1000)
            throw new IllegalArgumentException("memoryWindowSize must be between 1 and 1000");
        return new RoleConfigurationDto(normalizeOptionalText(config.role()), normalizeOptionalText(config.responsibility()),
            normalizeOptionalText(config.systemPrompt()), config.modelVersionId(), config.privacyModelVersionId(),
            config.privacyMappingEnabled(), config.knowledgeReleaseId(), config.memoryWindowSize(), skills, tools, playbooks);
    }

    public AgentProfile resolveRoleTemplateVersion(String versionId) {
        return withReadState(state -> {
            var version = state.findRoleTemplateVersion(versionId)
                .orElseThrow(() -> new NoSuchElementException("role template version not found: " + versionId));
            if (version.status() != VersionStatus.PUBLISHED) throw new IllegalStateException("role template version is not published");
            var template = requireTemplate(state, version.templateId());
            var config = normalizeRoleConfiguration(state, version.configuration(), true);
            var model = runtimeResource(state, config.modelVersionId(), ResourceType.LLM_MODEL);
            var privacy = hasText(config.privacyModelVersionId()) ? runtimeResource(state, config.privacyModelVersionId(), ResourceType.LLM_MODEL) : null;
            KnowledgeBindingSnapshotDto knowledge = null;
            if (hasText(config.knowledgeReleaseId())) {
                var release = requireKnowledgeRelease(config.knowledgeReleaseId(), true);
                var base = knowledgeService.getKnowledgeBase(release.knowledgeBaseId());
                knowledge = new KnowledgeBindingSnapshotDto(base.id(), base.name(), release.id(), release.version(),
                    release.snapshotId(), release.retrievalProfile().defaultTopK(), release.retrievalProfile().retrievalMode(), release.retrievalProfile().minScore());
            }
            return new AgentProfile(template.name(), config.role(), config.responsibility(), config.systemPrompt(),
                CatalogRuntimeMapper.toModelDescriptor(model), privacy == null ? null : CatalogRuntimeMapper.toModelDescriptor(privacy),
                config.privacyMappingEnabled(), CatalogRuntimeMapper.toKnowledgeBindingDescriptor(knowledge), config.memoryWindowSize(),
                config.skillVersionIds().stream().map(id -> CatalogRuntimeMapper.toSkillDescriptor(runtimeResource(state, id, ResourceType.SKILL))).toList(),
                config.toolVersionIds().stream().map(id -> CatalogRuntimeMapper.toToolDescriptor(runtimeResource(state, id, ResourceType.TOOL))).toList(),
                config.playbookVersionIds().stream().map(id -> runtimePlaybook(state, id)).toList());
        });
    }
    public PlaybookConfig resolvePlaybookVersion(String versionId) {
        return withReadState(state -> runtimePlaybook(state, versionId));
    }
    private PlaybookConfig runtimePlaybook(CatalogRepository state, String id) {
        var version = requirePlaybookVersion(state, id, true);
        var playbook = state.findPlaybook(version.playbookId()).orElseThrow();
        return CatalogRuntimeMapper.toPlaybookConfig(playbook, version);
    }
    private CatalogRuntimeMapper.ResolvedResource runtimeResource(CatalogRepository state, String id, ResourceType type) {
        var version = requireResourceVersion(state, id, type, true);
        var resource = findResource(state, version.resourceId());
        return new CatalogRuntimeMapper.ResolvedResource(resource.id(), resource.name(), version.id(), version.version(),
            materializeRuntimeConfiguration(version.configuration()));
    }

    public List<EntryDto> listEntries() { return withReadState(CatalogRepository::listEntries); }
    public EntryDto getEntry(String id) {
        return withReadState(state -> state.findEntry(id).orElseThrow(() -> new NoSuchElementException("entry not found: " + id)));
    }
    public EntryDto createEntry(EntryRequest request) { return saveEntry(nextId("entry"), request, false); }
    public EntryDto updateEntry(String id, EntryRequest request) { return saveEntry(id, request, true); }
    private EntryDto saveEntry(String id, EntryRequest request, boolean existing) {
        return withWriteState(state -> {
            if (existing && state.findEntry(id).isEmpty()) throw new NoSuchElementException("entry not found: " + id);
            CatalogOwnership.validate(request.ownerType(), request.ownerId());
            var version = state.findRoleTemplateVersion(requireText(request.receptionTemplateVersionId(), "entry.receptionTemplateVersionId"))
                .orElseThrow(() -> new NoSuchElementException("reception template version not found"));
            if (request.enabled() && version.status() != VersionStatus.PUBLISHED)
                throw new IllegalStateException("enabled entry requires a published reception template version");
            var allowed = distinct(request.allowedTemplateIds());
            allowed.forEach(templateId -> requireTemplate(state, templateId));
            var grants = request.capabilityGrants() == null ? List.<GrantRequest>of() : List.copyOf(request.capabilityGrants());
            for (var grant : grants) {
                requireText(grant.operation(), "grant.operation");
                java.util.Objects.requireNonNull(grant.scopeKind(), "grant.scopeKind");
                requireText(grant.scopeId(), "grant.scopeId");
                if (grant.expiresAt() != null && !grant.expiresAt().isAfter(Instant.now()))
                    throw new IllegalArgumentException("entry grant has expired");
            }
            var entry = new EntryDto(id, requireText(request.name(), "entry.name"), normalizeOptionalText(request.description()),
                request.ownerType(), request.ownerId(), request.shareScope() == null ? ShareScope.PRIVATE : request.shareScope(),
                version.id(), request.enabled(), allowed, grants);
            state.upsertEntry(entry);
            recordCatalogEvent(existing ? "ENTRY_UPDATED" : "ENTRY_CREATED", PlatformAggregateType.ENTRY, id, Map.of("name",entry.name(),"enabled",entry.enabled()));
            return entry;
        });
    }
    public EntryDto deleteEntry(String id) {
        return withWriteState(state -> { var existing = state.findEntry(id).orElseThrow(); state.deleteEntry(id); recordCatalogEvent("ENTRY_DELETED",PlatformAggregateType.ENTRY,id,Map.of()); return existing; });
    }

    public List<PlaybookDto> listPlaybooks() { return withReadState(state -> state.listPlaybooks().stream().map(p -> playbookView(state, p)).toList()); }
    public PlaybookVersionDto getPlaybookVersion(String versionId) { return withReadState(state -> requirePlaybookVersion(state, versionId, false)); }
    public PlaybookDto getPlaybook(String id) { return withReadState(state -> playbookView(state, requirePlaybook(state, id))); }
    public PlaybookDto createPlaybook(CreatePlaybookRequest request) {
        return withWriteState(state -> {
            CatalogOwnership.validate(request.ownerType(), request.ownerId());
            var item = new PlaybookDto(nextId("playbook"), requireText(request.name(), "playbook.name"), normalizeOptionalText(request.description()),
                request.ownerType(), request.ownerId(), null, null, List.of());
            state.upsertPlaybook(item);
            recordCatalogEvent("PLAYBOOK_CREATED",PlatformAggregateType.PLAYBOOK,item.id(),Map.of("name",item.name()));
            if (request.initialVersion() != null) createPlaybookVersion(state, item.id(), request.initialVersion());
            return playbookView(state, item);
        });
    }
    public PlaybookDto updatePlaybook(String id, UpdatePlaybookRequest request) {
        return withWriteState(state -> {
            requirePlaybook(state, id);
            CatalogOwnership.validate(request.ownerType(), request.ownerId());
            var item = new PlaybookDto(id, requireText(request.name(), "playbook.name"), normalizeOptionalText(request.description()),
                request.ownerType(), request.ownerId(), null, null, List.of());
            state.upsertPlaybook(item);
            recordCatalogEvent("PLAYBOOK_UPDATED",PlatformAggregateType.PLAYBOOK,id,Map.of("name",item.name()));
            return playbookView(state, item);
        });
    }
    public PlaybookDto deletePlaybook(String id) {
        return withWriteState(state -> {
            var item = playbookView(state, requirePlaybook(state, id));
            if (item.versions().stream().anyMatch(v -> v.status() == VersionStatus.PUBLISHED))
                throw new IllegalStateException("published playbooks are retained for existing instances");
            state.deletePlaybookVersions(id); state.deletePlaybook(id); recordCatalogEvent("PLAYBOOK_DELETED",PlatformAggregateType.PLAYBOOK,id,Map.of()); return item;
        });
    }
    public PlaybookVersionDto createPlaybookVersion(String id, CreatePlaybookVersionRequest request) {
        return withWriteState(state -> createPlaybookVersion(state, id, request));
    }
    private PlaybookVersionDto createPlaybookVersion(CatalogRepository state, String id, CreatePlaybookVersionRequest request) {
        requirePlaybook(state, id);
        var definition = normalizePlaybookDefinition(request.definition());
        validatePlaybookDefinition(definition);
        var versions = new ArrayList<>(state.listPlaybookVersions(id));
        var status = request.status() == null ? VersionStatus.DRAFT : request.status();
        var version = new PlaybookVersionDto(nextId("playbook-version"), id, nextVersion(versions.size()), status,
            Instant.now(), status == VersionStatus.PUBLISHED ? Instant.now() : null, definition);
        versions.add(version); state.replacePlaybookVersions(id, versions); recordVersionEvent(PlatformAggregateType.PLAYBOOK,id,version.id(),version.version(),version.status()); return version;
    }
    public PlaybookVersionDto publishPlaybookVersion(String id, String versionId) {
        return withWriteState(state -> {
            var version = requirePlaybookVersion(state, versionId, false);
            if (!version.playbookId().equals(id)) throw new NoSuchElementException("playbook version not found");
            if (version.status() == VersionStatus.PUBLISHED) return version;
            validatePlaybookDefinition(version.definition());
            var published = new PlaybookVersionDto(version.id(), id, version.version(), VersionStatus.PUBLISHED,
                version.createdAt(), Instant.now(), version.definition());
            state.replacePlaybookVersions(id, state.listPlaybookVersions(id).stream().map(v -> v.id().equals(versionId) ? published : v).toList());
            recordVersionEvent(PlatformAggregateType.PLAYBOOK,id,published.id(),published.version(),published.status());
            return published;
        });
    }
    private PlaybookDefinitionDto normalizePlaybookDefinition(PlaybookDefinitionDto definition) {
        if (definition == null) throw new IllegalArgumentException("playbook definition is required");
        return new PlaybookDefinitionDto(normalizeOptionalText(definition.description()), normalizeOptionalText(definition.inputSchema()),
            normalizeOptionalText(definition.resultSchema()), normalizePlaybookExecutionPolicy(definition.executionPolicy()),
            definition.allowHumanTask(), definition.allowExternalInteraction(), requireText(definition.entryNodeKey(), "playbook.entryNodeKey"),
            definition.nodes() == null ? List.of() : definition.nodes().stream().map(this::normalizePlaybookNode).toList(),
            definition.edges() == null ? List.of() : definition.edges().stream().map(this::normalizePlaybookEdge).toList());
    }

    public List<ResourceDto> listResources() { return withReadState(state -> state.listResources().stream().map(r -> toResourceView(state, r)).toList()); }
    public ResourceDto getResource(String id) { return withReadState(state -> toResourceView(state, findResource(state, id))); }
    public ResourceVersionDto getResourceVersion(String id) { return withReadState(state -> toResourceVersionDto(state.findResourceVersion(id).orElseThrow())); }
    public ResourceDto deleteResource(String id) {
        return withWriteState(state -> {
            var resource = toResourceView(state, findResource(state, id));
            if (resource.versions().stream().anyMatch(v -> v.status() == VersionStatus.PUBLISHED))
                throw new IllegalStateException("published resources are retained for existing instances");
            if (!resourceReferences(state, id).isEmpty()) throw new IllegalStateException("resource is referenced by role templates");
            state.deleteResourceVersions(id); state.deleteResource(id); recordCatalogEvent("RESOURCE_DELETED",PlatformAggregateType.RESOURCE,id,Map.of()); return resource;
        });
    }
    public KnowledgeBaseDto deleteKnowledgeBase(String id) { return knowledgeService.deleteKnowledgeBase(id); }
    public List<KnowledgeReferenceDto> listKnowledgeReferences(String id) { return knowledgeService.listKnowledgeReferences(id); }
    public ResourceCenterDto resourceCenter() {
        return withReadState(state -> new ResourceCenterDto(state.listResources().size(),
            (int) state.listResources().stream().filter(r -> r.shareScope() == ShareScope.SHARED).count(),
            (int) state.listResources().stream().filter(r -> r.shareScope() == ShareScope.PRIVATE).count(),
            state.listResources().stream().flatMap(resource -> resourceReferences(state, resource.id()).stream().map(ref ->
                new ResourceReferenceDto(resource.id(), resource.name(), resource.type(), resource.shareScope(), resource.ownerType() + ":" + resource.ownerId(),
                    null, null, ref.relationKind(), ref.targetType(), ref.targetId(), ref.targetName(), ref.resourceVersionId(), ref.resourceVersion(), true))).toList()));
    }
    public ObjectReferenceAnalysisDto objectReferences(String type, String id) {
        return withReadState(state -> switch (type.toUpperCase(Locale.ROOT)) {
            case "RESOURCE" -> new ObjectReferenceAnalysisDto("RESOURCE", id, findResource(state, id).name(), resourceReferences(state, id));
            case "KNOWLEDGE_BASE" -> ObjectReferenceAnalyzer.analyzeKnowledgeBase(knowledgeService.getKnowledgeBase(id), state);
            case "ROLE_TEMPLATE" -> {
                var template = requireTemplate(state, id);
                var relations = new ArrayList<ObjectReferenceRelationDto>();
                state.listEntries().stream().filter(e -> e.allowedTemplateIds().contains(id) || state.findRoleTemplateVersion(e.receptionTemplateVersionId()).map(v -> id.equals(v.templateId())).orElse(false))
                    .map(e -> ObjectReferenceAnalyzer.relation("ENTRY_TEMPLATE", "ENTRY", e.id(), e.name(), null, null, null, null)).forEach(relations::add);
                state.listRoleTemplateVersions(id).stream().filter(v -> v.status() == VersionStatus.PUBLISHED)
                    .map(v -> ObjectReferenceAnalyzer.relation("PUBLISHED_ROLE_VERSION", "ROLE_TEMPLATE_VERSION", v.id(), template.name() + "@" + v.version(), null, null, null, null)).forEach(relations::add);
                yield new ObjectReferenceAnalysisDto("ROLE_TEMPLATE", id, template.name(), List.copyOf(relations));
            }
            case "PLAYBOOK" -> {
                var playbook = requirePlaybook(state,id);
                var relations = new ArrayList<ObjectReferenceRelationDto>();
                templateVersions(state).stream().filter(v -> v.configuration().playbookVersionIds().stream().anyMatch(p -> state.findPlaybookVersion(p).map(pv -> pv.playbookId().equals(id)).orElse(false)))
                    .map(v -> ObjectReferenceAnalyzer.relation("ROLE_PLAYBOOK", "ROLE_TEMPLATE_VERSION", v.id(), v.templateId(), null, null, null, null)).forEach(relations::add);
                state.listPlaybookVersions(id).stream().filter(v -> v.status() == VersionStatus.PUBLISHED)
                    .map(v -> ObjectReferenceAnalyzer.relation("PUBLISHED_PLAYBOOK_VERSION", "PLAYBOOK_VERSION", v.id(), playbook.name() + "@" + v.version(), null, null, null, null)).forEach(relations::add);
                yield new ObjectReferenceAnalysisDto("PLAYBOOK", id, playbook.name(), List.copyOf(relations));
            }
            default -> throw new IllegalArgumentException("unsupported catalog object type: " + type);
        });
    }
    public DeletionImpactPreviewDto deletionPreview(String type, String id) {
        var refs = objectReferences(type, id);
        return new DeletionImpactPreviewDto(refs.objectType(), id, refs.objectName(), refs.relations().isEmpty(), refs.relations(), List.of(), List.of());
    }
    private List<ObjectReferenceRelationDto> resourceReferences(CatalogRepository state, String resourceId) {
        return ObjectReferenceAnalyzer.analyzeResource(findResource(state, resourceId), state).relations();
    }
    private String findResourceVersionDeletionBlocker(CatalogRepository state, String resourceId, String versionId) {
        return resourceReferences(state, resourceId).stream().filter(r -> versionId.equals(r.resourceVersionId()))
            .map(r -> "resource version is referenced by " + r.targetName()).findFirst().orElse(null);
    }

    private RoleTemplateDto requireTemplate(CatalogRepository state, String id) {
        return state.findRoleTemplate(id).orElseThrow(() -> new NoSuchElementException("role template not found: " + id));
    }
    private PlaybookDto requirePlaybook(CatalogRepository state, String id) {
        return state.findPlaybook(id).orElseThrow(() -> new NoSuchElementException("playbook not found: " + id));
    }
    private ResourceDto findResource(CatalogRepository state, String id) {
        return state.findResource(id).orElseThrow(() -> new NoSuchElementException("resource not found: " + id));
    }
    private StoredResourceVersion requireResourceVersion(CatalogRepository state, String versionId, ResourceType type, boolean published) {
        var version = state.findResourceVersion(versionId).orElseThrow(() -> new NoSuchElementException("resource version not found: " + versionId));
        if (findResource(state, version.resourceId()).type() != type) throw new IllegalArgumentException("resource version has wrong type: " + versionId);
        if (published && version.status() != VersionStatus.PUBLISHED) throw new IllegalStateException("resource version is not published: " + versionId);
        return version;
    }
    private PlaybookVersionDto requirePlaybookVersion(CatalogRepository state, String id, boolean published) {
        var version = state.findPlaybookVersion(id).orElseThrow(() -> new NoSuchElementException("playbook version not found: " + id));
        if (published && version.status() != VersionStatus.PUBLISHED) throw new IllegalStateException("playbook version is not published: " + id);
        return version;
    }
    private KnowledgeReleaseDto requireKnowledgeRelease(String id, boolean published) {
        var release = knowledgeRepository.listKnowledgeBases().stream().flatMap(k -> knowledgeRepository.listKnowledgeReleases(k.id()).stream())
            .filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("knowledge release not found: " + id));
        if (published && release.status() != VersionStatus.PUBLISHED) throw new IllegalStateException("knowledge release is not published: " + id);
        return release;
    }
    private RoleTemplateDto templateView(CatalogRepository state, RoleTemplateDto template) {
        var versions = state.listRoleTemplateVersions(template.id());
        return new RoleTemplateDto(template.id(), template.name(), template.description(), template.ownerType(), template.ownerId(),
            versions.isEmpty() ? null : versions.getLast(), versions.stream().filter(v -> v.status() == VersionStatus.PUBLISHED).reduce((a,b) -> b).orElse(null), versions);
    }
    private PlaybookDto playbookView(CatalogRepository state, PlaybookDto playbook) {
        var versions = state.listPlaybookVersions(playbook.id());
        return new PlaybookDto(playbook.id(), playbook.name(), playbook.description(), playbook.ownerType(), playbook.ownerId(),
            versions.isEmpty() ? null : versions.getLast(), versions.stream().filter(v -> v.status() == VersionStatus.PUBLISHED).reduce((a,b) -> b).orElse(null), versions);
    }
    private ResourceDto toResourceView(CatalogRepository state, ResourceDto resource) {
        var versions = versionsFor(state, resource.id());
        return new ResourceDto(resource.id(), resource.name(), resource.type(), resource.shareScope(), resource.ownerType(), resource.ownerId(), resource.summary(), resource.steward(), resource.tags(),
            versions.isEmpty() ? null : versions.getLast(), versions.stream().filter(v -> v.status() == VersionStatus.PUBLISHED).reduce((a,b) -> b).orElse(null), versions);
    }
    private ResourceVersionDto findResourceVersion(CatalogRepository state, String resourceId, String versionId) {
        return versionsFor(state, resourceId).stream().filter(v -> v.id().equals(versionId)).findFirst().orElseThrow();
    }
    private List<ResourceVersionDto> versionsFor(CatalogRepository state, String id) { return storedVersionsFor(state, id).stream().map(this::toResourceVersionDto).toList(); }
    private List<StoredResourceVersion> storedVersionsFor(CatalogRepository state, String id) { return state.listResourceVersions(id); }
    private List<RoleTemplateVersionDto> templateVersions(CatalogRepository state) { return state.listRoleTemplates().stream().flatMap(t -> state.listRoleTemplateVersions(t.id()).stream()).toList(); }
    private static String nextId(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", ""); }
    private static String nextVersion(int count) { return "1.0." + count; }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static List<String> distinct(List<String> values) { return values == null ? List.of() : values.stream().map(v -> requireText(v, "version reference")).distinct().toList(); }

    public ResourceDto createResource(CreateResourceRequest request) {
        return withWriteState(state -> {
        CatalogOwnership.validate(request.ownerType(), request.ownerId());
        java.util.Objects.requireNonNull(request.type(), "resource.type");
        String resourceId = nextId("resource");
        ResourceDto resource = new ResourceDto(
            resourceId,
            requireText(request.name(), "resource.name"),
            request.type(),
            request.shareScope() == null ? ShareScope.PRIVATE : request.shareScope(),
            request.ownerType(),
            request.ownerId(),
            normalizeOptionalText(request.summary()),
            normalizeOptionalText(request.steward()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            null,
            null,
            List.of()
        );
        state.upsertResource(resource);
        createResourceVersion(
            state,
            resourceId,
            request.initialVersion() == null
                ? new CreateResourceVersionRequest("初始版本", VersionStatus.DRAFT, defaultConfiguration(resource.type()))
                : normalizeInitialVersionRequest(resource.type(), request.initialVersion())
        );
        recordCatalogEvent("RESOURCE_CREATED", PlatformAggregateType.RESOURCE, resource.id(), Map.of("name", resource.name()));
        return toResourceView(state, resource);
        });
    }

    public List<ResourceVersionDto> listResourceVersions(String resourceId) {
        return withReadState(state -> {
        findResource(state, resourceId);
        return versionsFor(state, resourceId);
        });
    }

    public ResourceDto updateResource(String resourceId, UpdateResourceRequest request) {
        return withWriteState(state -> {
        ResourceDto existing = findResource(state, resourceId);
        String name = requireText(request.name(), "resource.name");
        CatalogOwnership.validate(request.ownerType(), request.ownerId());
        ResourceDto updated = new ResourceDto(
            existing.id(),
            name,
            existing.type(),
            request.shareScope() == null ? existing.shareScope() : request.shareScope(),
            request.ownerType(),
            request.ownerId(),
            normalizeOptionalText(request.summary()),
            normalizeOptionalText(request.steward()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            existing.latestVersion(),
            existing.effectiveVersion(),
            existing.versions()
        );
        state.upsertResource(updated);
        recordCatalogEvent("RESOURCE_UPDATED", PlatformAggregateType.RESOURCE, updated.id(), Map.of("name", updated.name()));
        return toResourceView(state, updated);
        });
    }

    public ResourceVersionDto createResourceVersion(String resourceId, CreateResourceVersionRequest request) {
        return withWriteState(state -> {
        return createResourceVersion(state, resourceId, request);
        });
    }

    private ResourceVersionDto createResourceVersion(
        CatalogRepository state,
        String resourceId,
        CreateResourceVersionRequest request
    ) {
        ResourceDto resource = findResource(state, resourceId);
        VersionStatus status = request.status() == null ? VersionStatus.DRAFT : request.status();
        ResourceVersionConfigurationDto normalizedConfiguration = normalizeConfiguration(resource.type(), request.configuration());
        String configDigest = generateConfigDigest(normalizedConfiguration);
        if (status == VersionStatus.PUBLISHED) {
            validateVersionReadyForActivation(resource.type(), normalizedConfiguration);
        }
        List<StoredResourceVersion> existingVersions = new ArrayList<>(storedVersionsFor(state, resourceId));
        StoredResourceVersion created = new StoredResourceVersion(
            nextId("resource-version"),
            resource.id(),
            nextResourceVersion(existingVersions),
            status,
            request.summary(),
            configDigest,
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null,
            normalizedConfiguration
        );
        existingVersions.add(created);
        state.replaceResourceVersions(resourceId, existingVersions);
        recordCatalogEvent(
            "RESOURCE_VERSION_CREATED",
            PlatformAggregateType.RESOURCE,
            resource.id(),
            Map.of("name", resource.name(), "versionId", created.id(), "version", created.version(), "status", created.status().name())
        );
        if (created.status() == VersionStatus.PUBLISHED) {
            recordCatalogEvent(
                "RESOURCE_VERSION_PUBLISHED",
                PlatformAggregateType.RESOURCE,
                resource.id(),
                Map.of("name", resource.name(), "versionId", created.id(), "version", created.version(), "status", created.status().name())
            );
        }
        return toResourceVersionDto(created);
    }

    public ResourceVersionDto updateResourceVersion(String resourceId, String versionId, UpdateResourceVersionRequest request) {
        return withWriteState(state -> {
        ResourceDto resource = findResource(state, resourceId);
        StoredResourceVersion existing = storedVersionsFor(state, resourceId).stream()
            .filter(item -> item.id().equals(versionId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("resource version not found: " + resourceId + "/" + versionId));
        if (existing.status() == VersionStatus.PUBLISHED) {
            throw new IllegalStateException("published resource version cannot be updated directly: " + resourceId + "/" + versionId);
        }

        VersionStatus targetStatus = request.status() == null ? VersionStatus.DRAFT : request.status();
        ResourceVersionConfigurationDto normalizedConfiguration = normalizeConfiguration(resource.type(), request.configuration());
        if (targetStatus == VersionStatus.PUBLISHED) {
            validateVersionReadyForActivation(resource.type(), normalizedConfiguration);
        }
        String summary = normalizeOptionalText(request.summary());
        String configDigest = generateConfigDigest(normalizedConfiguration);
        Instant publishedAt = targetStatus == VersionStatus.PUBLISHED ? Instant.now() : null;

        List<StoredResourceVersion> updatedVersions = storedVersionsFor(state, resourceId).stream()
            .map(version -> {
                if (version.id().equals(versionId)) {
                    return new StoredResourceVersion(
                        version.id(),
                        version.resourceId(),
                        version.version(),
                        targetStatus,
                        summary,
                        configDigest,
                        version.createdAt(),
                        publishedAt,
                        normalizedConfiguration
                    );
                }
                return version;
            })
            .toList();
        state.replaceResourceVersions(resourceId, updatedVersions);
        ResourceVersionDto updatedVersion = updatedVersions.stream()
            .filter(item -> item.id().equals(versionId))
            .map(this::toResourceVersionDto)
            .findFirst()
            .orElseThrow();
        recordCatalogEvent(
            "RESOURCE_VERSION_UPDATED",
            PlatformAggregateType.RESOURCE,
            resource.id(),
            Map.of(
                "name", resource.name(),
                "versionId", updatedVersion.id(),
                "version", updatedVersion.version(),
                "status", updatedVersion.status().name()
            )
        );
        if (updatedVersion.status() == VersionStatus.PUBLISHED) {
            recordCatalogEvent(
                "RESOURCE_VERSION_PUBLISHED",
                PlatformAggregateType.RESOURCE,
                resource.id(),
                Map.of(
                    "name", resource.name(),
                    "versionId", updatedVersion.id(),
                    "version", updatedVersion.version(),
                    "status", updatedVersion.status().name()
                )
            );
        }
        return updatedVersion;
        });
    }

    public ResourceVersionDto publishResourceVersion(String resourceId, String versionId) {
        return withWriteState(state -> {
        ResourceDto resource = findResource(state, resourceId);
        ResourceVersionDto targetVersion = findResourceVersion(state, resourceId, versionId);
        validateVersionReadyForActivation(resource.type(), targetVersion.configuration());
        List<StoredResourceVersion> updatedVersions = storedVersionsFor(state, resourceId).stream()
            .map(version -> new StoredResourceVersion(
                version.id(),
                version.resourceId(),
                version.version(),
                version.id().equals(versionId) ? VersionStatus.PUBLISHED : version.status(),
                version.summary(),
                version.configDigest(),
                version.createdAt(),
                version.id().equals(versionId) ? (version.publishedAt() == null ? Instant.now() : version.publishedAt()) : version.publishedAt(),
                version.configuration()
            ))
            .toList();
        state.replaceResourceVersions(resourceId, updatedVersions);
        ResourceVersionDto publishedVersion = updatedVersions.stream()
            .filter(item -> item.id().equals(versionId))
            .map(this::toResourceVersionDto)
            .findFirst()
            .orElseThrow();
        recordCatalogEvent(
            "RESOURCE_VERSION_PUBLISHED",
            PlatformAggregateType.RESOURCE,
            resource.id(),
            Map.of(
                "name", resource.name(),
                "versionId", publishedVersion.id(),
                "version", publishedVersion.version(),
                "status", publishedVersion.status().name()
            )
        );
        return publishedVersion;
        });
    }

    public ResourceVersionDto deleteResourceVersion(String resourceId, String versionId) {
        return withWriteState(state -> {
        ResourceDto resource = toResourceView(state, findResource(state, resourceId));
        ResourceVersionDto version = findResourceVersion(state, resourceId, versionId);
        if (version.status() == VersionStatus.PUBLISHED) {
            throw new IllegalStateException("resource effective version cannot be deleted: " + resourceId + "/" + versionId);
        }
        if (versionsFor(state, resourceId).size() <= 1) {
            throw new IllegalStateException("resource must keep at least one version: " + resourceId);
        }

        String versionPinBlocker = findResourceVersionDeletionBlocker(state, resourceId, versionId);
        if (versionPinBlocker != null) {
            throw new IllegalStateException(versionPinBlocker);
        }

        List<StoredResourceVersion> updatedVersions = storedVersionsFor(state, resourceId).stream()
            .filter(item -> !item.id().equals(versionId))
            .toList();
        state.replaceResourceVersions(resourceId, updatedVersions);
        recordCatalogEvent(
            "RESOURCE_VERSION_DELETED",
            PlatformAggregateType.RESOURCE,
            resource.id(),
            Map.of("name", resource.name(), "versionId", version.id(), "version", version.version(), "status", version.status().name())
        );
        return version;
        });
    }

    public List<ResourceBlueprintDto> resourceBlueprints() {
        return withReadState(state -> {
        return List.of(
            new ResourceBlueprintDto(
                ResourceType.TOOL,
                "Tool",
                "承载 agent 可调用的业务能力，并通过 connector 绑定接入实现和账号。",
                List.of("操作定义", "Connector 类型", "Integration Account", "超时设置", "重试策略", "Connector 配置", "操作映射"),
                defaultConfiguration(ResourceType.TOOL)
            ),
            new ResourceBlueprintDto(
                ResourceType.LLM_MODEL,
                "LLM 模型",
                "承载多供应商模型连接、默认参数和鉴权入口，供角色模板固定引用并执行真实模型调用。",
                List.of("供应商类型", "模型 ID", "Base URL", "API Key 环境变量", "组织/项目/区域", "Temperature", "Max Tokens"),
                defaultConfiguration(ResourceType.LLM_MODEL)
            ),
            new ResourceBlueprintDto(
                ResourceType.SKILL,
                "Skill",
                "承载供智能体按需读取的行为模式说明，包括技能名称、描述和完整技能提示。",
                List.of("技能名称", "技能描述", "技能提示"),
                defaultConfiguration(ResourceType.SKILL)
            )
        );
        });
    }

    private void recordVersionEvent(PlatformAggregateType type,String aggregateId,String versionId,String version,VersionStatus status) {
        recordCatalogEvent(type.name() + (status == VersionStatus.PUBLISHED ? "_VERSION_PUBLISHED" : "_VERSION_CREATED"), type, aggregateId,
            Map.of("versionId",versionId,"version",version,"status",status.name()));
    }

    private void recordCatalogEvent(String eventType, PlatformAggregateType aggregateType, String aggregateId, Map<String, Object> payload) {
        platformEventService.recordControlEvent(eventType, aggregateType, aggregateId, payload);
    }

    private ResourceVersionConfigurationDto normalizeConfiguration(ResourceType type, ResourceVersionConfigurationDto configuration) {
        if (configuration == null) {
            if (type == ResourceType.TOOL) {
                throw new IllegalArgumentException("tool resource version configuration is required");
            }
            return defaultConfiguration(type);
        }
        return switch (type) {
            case TOOL -> new ResourceVersionConfigurationDto(type, normalizeRequiredToolConfig(configuration.tool()), null, null);
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
                null,
                normalizeLlmModelConfig(configuration.llmModel() == null ? defaultConfiguration(type).llmModel() : configuration.llmModel()),
                null
            );
            case SKILL -> new ResourceVersionConfigurationDto(type, null, null, configuration.skill() == null ? defaultConfiguration(type).skill() : normalizeSkillConfig(configuration.skill()));
        };
    }

    private ResourceVersionConfigurationDto defaultConfiguration(ResourceType type) {
        return switch (type) {
            case TOOL -> new ResourceVersionConfigurationDto(
                type,
                defaultToolConfig(),
                null,
                null
            );
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
                null,
                new LlmModelConfigDto(
                    "OPENAI_COMPATIBLE",
                    "",
                    "",
                    "OPENAI_COMPATIBLE_API_KEY",
                    0.2,
                    1200,
                    false,
                    null,
                    null
                ),
                null
            );
            case SKILL -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                new SkillConfigDto("新技能", "请填写技能用途说明。", "请填写技能行为说明。")
            );
        };
    }

    private void validateVersionReadyForActivation(ResourceType type, ResourceVersionConfigurationDto configuration) {
        if (type == ResourceType.LLM_MODEL) {
            var model = configuration.llmModel();
            requireText(model.providerType(), "model.providerType");
            requireText(model.modelId(), "model.modelId");
            requireText(model.baseUrl(), "model.baseUrl");
            requireText(model.apiKeyEnvVar(), "model.apiKeyEnvVar");
            if (!Set.of("OPENAI", "OPENAI_COMPATIBLE").contains(model.providerType().toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("runtime currently supports OPENAI and OPENAI_COMPATIBLE model providers");
        }
        if (type != ResourceType.TOOL && type != ResourceType.LLM_MODEL && type != ResourceType.SKILL) {
            throw new IllegalStateException("unsupported resource type: " + type);
        }
    }

    private CreateResourceVersionRequest normalizeInitialVersionRequest(ResourceType type, CreateResourceVersionRequest request) {
        ResourceVersionConfigurationDto configuration = normalizeConfiguration(type, request.configuration());
        VersionStatus status = request.status() == null ? VersionStatus.DRAFT : request.status();
        return new CreateResourceVersionRequest(
            normalizeOptionalText(request.summary()).isBlank() ? "初始版本" : normalizeOptionalText(request.summary()),
            status,
            configuration
        );
    }

    private String generateConfigDigest(ResourceVersionConfigurationDto configuration) {
        String fingerprint = configuration == null ? "empty" : configuration.toString();
        return "cfg-" + UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 12);
    }

    private SkillConfigDto normalizeSkillConfig(SkillConfigDto configuration) {
        if (configuration == null) {
            return defaultConfiguration(ResourceType.SKILL).skill();
        }
        String skillName = normalizeOptionalText(configuration.skillName());
        String skillDesc = normalizeOptionalText(configuration.skillDesc());
        String skillPrompt = normalizeOptionalText(configuration.skillPrompt());
        return new SkillConfigDto(
            skillName.isBlank() ? "未命名技能" : skillName,
            skillDesc,
            skillPrompt.isBlank() ? "请补充技能行为说明。" : skillPrompt
        );
    }

    private ToolConfigDto defaultToolConfig() {
        return new ToolConfigDto(
            defaultToolOperations(),
            null
        );
    }

    private ToolConfigDto normalizeToolConfig(ToolConfigDto configuration) {
        List<ToolOperationDto> operations = normalizeToolOperations(configuration == null ? null : configuration.operations());
        if (operations.isEmpty()) {
            operations = defaultToolOperations();
        }

        return new ToolConfigDto(
            List.copyOf(operations),
            normalizeToolConnector(configuration == null ? null : configuration.connector(), operations)
        );
    }

    private ToolConfigDto normalizeRequiredToolConfig(ToolConfigDto configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("tool config is required");
        }
        return normalizeToolConfig(configuration);
    }

    private List<ToolOperationDto> defaultToolOperations() {
        return List.of(new ToolOperationDto("invoke", "执行通用工具动作", "{\"input\":\"string\"}", "{\"type\":\"object\",\"required\":[\"output\"],\"properties\":{\"output\":{\"type\":\"string\"}},\"additionalProperties\":false}"));
    }

    private List<ToolOperationDto> normalizeToolOperations(List<ToolOperationDto> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        List<ToolOperationDto> normalized = new ArrayList<>();
        for (ToolOperationDto operation : operations) {
            if (operation == null) {
                continue;
            }
            String name = normalizeOptionalText(operation.name());
            if (name.isBlank()) {
                continue;
            }
            normalized.add(new ToolOperationDto(
                name,
                normalizeOptionalText(operation.description()),
                normalizeOptionalText(operation.inputSchema()),
                normalizeOptionalText(operation.outputSchema())
            ));
        }
        return List.copyOf(normalized);
    }

    private ToolConnectorConfigDto normalizeToolConnector(ToolConnectorConfigDto configuration, List<ToolOperationDto> operations) {
        if (configuration == null) {
            throw new IllegalArgumentException("tool connector config is required");
        }

        String connectorType = requireToolConnectorType(configuration.connectorType());
        ToolConnectorDefinition definition = requireToolConnectorDefinition(connectorType);
        Map<String, Object> config = immutableObjectMap(configuration.config(), "tool connector config");
        rejectSensitiveMaterial(config, "connector.config");
        validateSchema(definition.configSchema(), config, "tool connector config");

        String accountId = normalizeOptionalText(configuration.accountId());
        accountId = accountId.isBlank() ? null : accountId;

        Map<String, Map<String, Object>> requestedMappings = configuration.operationMappings();
        if (requestedMappings == null) {
            throw new IllegalArgumentException("tool connector operationMappings are required");
        }
        Set<String> operationNames = new HashSet<>();
        for (ToolOperationDto operation : operations) {
            operationNames.add(operation.name());
        }
        for (String mappingName : requestedMappings.keySet()) {
            if (!operationNames.contains(mappingName)) {
                throw new IllegalArgumentException("tool connector operationMappings contains unknown operation: " + mappingName);
            }
        }

        Map<String, Map<String, Object>> operationMappings = new LinkedHashMap<>();
        for (ToolOperationDto operation : operations) {
            if (!requestedMappings.containsKey(operation.name())) {
                throw new IllegalArgumentException("tool connector operationMappings missing operation: " + operation.name());
            }
            Map<String, Object> mapping = immutableObjectMap(requestedMappings.get(operation.name()), "tool connector operation mapping " + operation.name());
            rejectSensitiveMaterial(mapping, "connector.operationMappings." + operation.name());
            validateSchema(definition.operationMappingSchema(), mapping, "tool connector operation mapping " + operation.name());
            operationMappings.put(operation.name(), mapping);
        }
        return new ToolConnectorConfigDto(
            connectorType,
            accountId,
            null,
            configuration.timeoutSeconds() <= 0 ? DEFAULT_TOOL_CONNECTOR_TIMEOUT_SECONDS : configuration.timeoutSeconds(),
            configuration.retryPolicy() == null || configuration.retryPolicy().isBlank() ? DEFAULT_TOOL_CONNECTOR_RETRY_POLICY : configuration.retryPolicy(),
            config,
            Collections.unmodifiableMap(operationMappings)
        );
    }

    private ToolConnectorDefinition requireToolConnectorDefinition(String connectorType) {
        return toolConnectorDefinitions().stream()
            .filter(definition -> connectorType.equals(definition.connectorType()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("tool connector definition not found: " + connectorType));
    }

    private List<ToolConnectorDefinition> toolConnectorDefinitions() {
        if (extensionDefinitionService != null) {
            return extensionDefinitionService.toolConnectors();
        }
        throw new IllegalStateException("tool connector definition registry is unavailable; ExtensionDefinitionService is required");
    }

    private static String requireToolConnectorType(String connectorType) {
        String normalized = normalizeOptionalText(connectorType);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("tool connector connectorType is required");
        }
        return normalized;
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(label + " contains a blank key");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void validateSchema(Map<String, Object> schema, Map<String, Object> value, String label) {
        try {
            JsonSchemaValues.validate(schema, value, TOOL_CONNECTOR_CONFIG_SCHEMA_ID);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " does not satisfy connector schema", error);
        }
    }

    private static void rejectSensitiveMaterial(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String keyText ? keyText : String.valueOf(entry.getKey());
                if (SENSITIVE_CONFIG_KEYS.contains(normalizeSensitiveKey(key))) {
                    throw new IllegalArgumentException("tool connector user-editable config contains secret-like key: " + path + "." + key);
                }
                rejectSensitiveMaterial(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                rejectSensitiveMaterial(item, path + "[" + index + "]");
                index++;
            }
        }
    }

    private static String normalizeSensitiveKey(String key) {
        return normalizeOptionalText(key)
            .replace("_", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
    }

    private LlmModelConfigDto normalizeLlmModelConfig(LlmModelConfigDto config) {
        if (config == null) {
            return defaultConfiguration(ResourceType.LLM_MODEL).llmModel();
        }
        return new LlmModelConfigDto(
            normalizeOptionalText(config.providerType()),
            normalizeOptionalText(config.modelId()),
            normalizeOptionalText(config.baseUrl()),
            normalizeOptionalText(config.apiKeyEnvVar()),
            config.temperature(),
            config.maxTokens(),
            config.privateDeployment(),
            config.enableThinking(),
            normalizeReasoningEffort(config.reasoningEffort(), "llmModel.reasoningEffort")
        );
    }

    private String normalizeReasoningEffort(String value, String fieldName) {
        String normalized = normalizeOptionalText(value);
        if (normalized.isBlank()) {
            return null;
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!REASONING_EFFORT_VALUES.contains(lowered)) {
            throw new IllegalArgumentException(fieldName + " must be one of " + REASONING_EFFORT_VALUES);
        }
        return lowered;
    }

    private String nextResourceVersion(List<StoredResourceVersion> versions) {
        if (versions.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = versions.getLast().version().split("\\.");
        int patch = Integer.parseInt(segments[2]) + 1;
        return segments[0] + "." + segments[1] + "." + patch;
    }

    private ResourceVersionDto toResourceVersionDto(StoredResourceVersion version) {
        return redactToolConnectorSecrets(new ResourceVersionDto(
            version.id(),
            version.resourceId(),
            version.version(),
            version.status(),
            version.summary(),
            version.createdAt(),
            version.publishedAt(),
            version.configuration()
        ));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private ToolConnectorAccountSnapshotDto materializeAccountSnapshot(String connectorType, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        if (integrationAccountService == null || integrationAccountRepository == null) {
            throw new IllegalStateException("integration account materialization is unavailable");
        }
        integrationAccountService.requireAccountAvailability(
            accountId,
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            connectorType
        );
        StoredIntegrationAccount account = integrationAccountRepository.findAccount(accountId)
            .orElseThrow(() -> new NoSuchElementException("integration account not found: " + accountId));
        String runtimeSecretRef = normalizeOptionalText(account.externalSecretRef());
        return new ToolConnectorAccountSnapshotDto(
            account.id(),
            runtimeSecretRef.isBlank() ? null : runtimeSecretRef
        );
    }

    private ResourceVersionConfigurationDto materializeRuntimeConfiguration(ResourceVersionConfigurationDto configuration) {
        if (configuration == null || configuration.type() != ResourceType.TOOL || configuration.tool() == null) {
            return configuration;
        }
        ToolConfigDto normalizedTool = normalizeToolConfig(configuration.tool());
        ToolConnectorConfigDto connector = normalizedTool.connector();
        ToolConnectorConfigDto releaseConnector = new ToolConnectorConfigDto(
            connector.connectorType(),
            null,
            materializeAccountSnapshot(connector.connectorType(), connector.accountId()),
            connector.timeoutSeconds(),
            connector.retryPolicy(),
            connector.config(),
            connector.operationMappings()
        );
        return new ResourceVersionConfigurationDto(
            ResourceType.TOOL,
            new ToolConfigDto(normalizedTool.operations(), releaseConnector),
            null,
            null
        );
    }

    private PlaybookExecutionPolicyDto normalizePlaybookExecutionPolicy(PlaybookExecutionPolicyDto policy) {
        if (policy == null) {
            return new PlaybookExecutionPolicyDto(null, null);
        }
        return new PlaybookExecutionPolicyDto(
            normalizeOptionalText(policy.timeoutPolicy()),
            normalizeOptionalText(policy.retryPolicy())
        );
    }

    private PlaybookNodeDto normalizePlaybookNode(PlaybookNodeDto node) {
        return new PlaybookNodeDto(
            requireText(node.nodeKey(), "playbook.node.nodeKey"),
            requireText(node.nodeName(), "playbook.node.nodeName"),
            node.nodeType(),
            normalizeOptionalText(node.description()),
            normalizeOptionalText(node.scriptRef()),
            normalizeOptionalText(node.scriptVersion()),
            normalizeOptionalText(node.toolId()),
            normalizeOptionalText(node.toolOperation()),
            node.config() == null ? Map.of() : Map.copyOf(node.config()),
            normalizePlaybookNodeLayout(node.layout())
        );
    }

    private PlaybookNodeLayoutDto normalizePlaybookNodeLayout(PlaybookNodeLayoutDto layout) {
        if (layout == null) {
            return new PlaybookNodeLayoutDto(0, 0);
        }
        return new PlaybookNodeLayoutDto(layout.x(), layout.y());
    }

    private PlaybookEdgeDto normalizePlaybookEdge(PlaybookEdgeDto edge) {
        return new PlaybookEdgeDto(
            requireText(edge.edgeKey(), "playbook.edge.edgeKey"),
            requireText(edge.sourceNodeKey(), "playbook.edge.sourceNodeKey"),
            requireText(edge.targetNodeKey(), "playbook.edge.targetNodeKey"),
            normalizeOptionalText(edge.routeKey()),
            normalizeOptionalText(edge.label()),
            edge.defaultEdge()
        );
    }

    private void validatePlaybookDefinition(PlaybookDefinitionDto playbook) {
        if (playbook.nodes().isEmpty()) {
            throw new IllegalStateException("playbook must contain at least one node");
        }
        Set<String> nodeKeys = new HashSet<>();
        Set<String> edgeKeys = new HashSet<>();
        for (PlaybookNodeDto node : playbook.nodes()) {
            if (!nodeKeys.add(node.nodeKey())) {
                throw new IllegalStateException("duplicate playbook nodeKey: " + node.nodeKey());
            }
            if (node.nodeType() == null) {
                throw new IllegalStateException("playbook nodeType is required");
            }
            if (node.layout() == null) {
                throw new IllegalStateException("playbook node layout is required");
            }
            if (node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.STEP) {
                validateStepNode(node);
            }
            if (node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.TOOL_TASK) {
                validateToolTaskNode(node);
            }
            if (node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.EXTERNAL_INTERACTION) {
                Object source = node.config().get("externalSource");
                if (!(source instanceof Map<?,?> binding) || !Set.of("TOOL_CONNECTOR", "CHANNEL_PROVIDER").contains(binding.get("subjectType"))
                    || !(binding.get("descriptorId") instanceof String descriptor) || descriptor.isBlank()
                    || !(binding.get("accountId") instanceof String account) || account.isBlank())
                    throw new IllegalStateException("EXTERNAL_INTERACTION requires config.externalSource with subjectType, descriptorId and accountId");
            }
            if (!playbook.allowHumanTask() && node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.HUMAN_TASK) {
                throw new IllegalStateException("playbook does not allow HUMAN_TASK nodes");
            }
            if (!playbook.allowExternalInteraction()
                && node.nodeType() == com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType.EXTERNAL_INTERACTION) {
                throw new IllegalStateException("playbook does not allow EXTERNAL_INTERACTION nodes");
            }
        }
        if (!nodeKeys.contains(playbook.entryNodeKey())) {
            throw new IllegalStateException("playbook entryNodeKey must reference an existing node");
        }
        for (PlaybookEdgeDto edge : playbook.edges()) {
            if (!edgeKeys.add(edge.edgeKey())) {
                throw new IllegalStateException("duplicate playbook edgeKey: " + edge.edgeKey());
            }
            if (!nodeKeys.contains(edge.sourceNodeKey()) || !nodeKeys.contains(edge.targetNodeKey())) {
                throw new IllegalStateException("playbook edge must reference existing nodes");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateStepNode(PlaybookNodeDto node) {
        if (node.scriptRef() == null || node.scriptRef().isBlank() || node.scriptVersion() == null || node.scriptVersion().isBlank()) {
            throw new IllegalStateException("STEP node must define scriptRef and scriptVersion");
        }
        Object rawScriptVersions = node.config().get("scriptVersions");
        if (!(rawScriptVersions instanceof Map<?, ?> scriptVersions)) {
            throw new IllegalStateException("STEP node must define config.scriptVersions");
        }
        Object rawVersionConfig = scriptVersions.get(node.scriptVersion());
        if (!(rawVersionConfig instanceof Map<?, ?> versionConfig)) {
            throw new IllegalStateException("STEP node scriptVersion must resolve to config.scriptVersions entry");
        }
        Object rawCode = ((Map<String, Object>) versionConfig).get("code");
        if (!(rawCode instanceof String code) || code.isBlank()) {
            throw new IllegalStateException("STEP node versioned script config must define non-empty code");
        }
    }

    private void validateToolTaskNode(PlaybookNodeDto node) {
        if (node.toolId() == null || node.toolId().isBlank() || node.toolOperation() == null || node.toolOperation().isBlank()) {
            throw new IllegalStateException("TOOL_TASK node must define toolId and toolOperation");
        }
    }

    private ResourceVersionDto redactToolConnectorSecrets(ResourceVersionDto version) {
        return new ResourceVersionDto(
            version.id(),
            version.resourceId(),
            version.version(),
            version.status(),
            version.summary(),
            version.createdAt(),
            version.publishedAt(),
            redactToolConnectorSecrets(version.configuration())
        );
    }

    private ResourceVersionConfigurationDto redactToolConnectorSecrets(ResourceVersionConfigurationDto configuration) {
        if (configuration == null || configuration.tool() == null || configuration.tool().connector() == null) {
            return configuration;
        }
        ToolConfigDto tool = configuration.tool();
        ToolConnectorConfigDto connector = tool.connector();
        ToolConnectorAccountSnapshotDto redactedSnapshot = redactToolConnectorSecrets(connector.accountSnapshot());
        return new ResourceVersionConfigurationDto(
            configuration.type(),
            new ToolConfigDto(
                tool.operations(),
                new ToolConnectorConfigDto(
                    connector.connectorType(),
                    connector.accountId(),
                    redactedSnapshot,
                    connector.timeoutSeconds(),
                    connector.retryPolicy(),
                    connector.config(),
                    connector.operationMappings()
                )
            ),
            configuration.llmModel(),
            configuration.skill()
        );
    }

    private ToolConnectorAccountSnapshotDto redactToolConnectorSecrets(ToolConnectorAccountSnapshotDto accountSnapshot) {
        if (accountSnapshot == null) {
            return null;
        }
        return new ToolConnectorAccountSnapshotDto(
            accountSnapshot.accountId(),
            accountSnapshot.hasExternalSecretRef() || !normalizeOptionalText(accountSnapshot.runtimeSecretRef()).isBlank(),
            null
        );
    }

    private static final class NoOpKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        @Override public void startImport(String knowledgeBaseId, String importJobId) {}
        @Override public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {}
    }
}
