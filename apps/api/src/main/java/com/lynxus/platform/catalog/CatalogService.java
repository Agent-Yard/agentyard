package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.lynxus.platform.event.PlatformEventService;
import com.lynxus.platform.knowledge.InMemoryKnowledgeRepository;
import com.lynxus.platform.knowledge.KnowledgeService;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolProviderType;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogRepository repository;
    private final KnowledgeService knowledgeService;
    private final PlatformEventService platformEventService;
    private boolean initialized;
    private final List<BusinessDomainDto> domains = new ArrayList<>();
    private final List<ScenarioDto> scenarios = new ArrayList<>();
    private final List<AssistantDto> assistants = new ArrayList<>();
    private final List<AgentDto> agents = new ArrayList<>();
    private final List<PlaybookDto> playbooks = new ArrayList<>();
    private final List<ResourceDto> resources = new ArrayList<>();
    private final Map<String, List<StoredResourceVersion>> resourceVersions = new LinkedHashMap<>();
    private final Map<String, List<AssistantReleaseDto>> assistantReleases = new LinkedHashMap<>();

    public CatalogService() {
        this(
            new InMemoryCatalogRepository(),
            new InMemoryKnowledgeRepository(),
            new KnowledgeServiceClient("http://127.0.0.1:8091", "in-memory-internal-token"),
            new NoOpKnowledgeWorkflowGateway(),
            PlatformEventService.disabled()
        );
    }

    public CatalogService(CatalogRepository repository, KnowledgeService knowledgeService) {
        this(repository, knowledgeService, PlatformEventService.disabled());
    }

    @Autowired
    public CatalogService(CatalogRepository repository, KnowledgeService knowledgeService, PlatformEventService platformEventService) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
        this.platformEventService = platformEventService;
    }

    public CatalogService(CatalogRepository repository, KnowledgeServiceClient knowledgeServiceClient, KnowledgeWorkflowGateway knowledgeWorkflowGateway) {
        this(repository, new InMemoryKnowledgeRepository(), knowledgeServiceClient, knowledgeWorkflowGateway, PlatformEventService.disabled());
    }

    public CatalogService(
        CatalogRepository repository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService
    ) {
        this(repository, new InMemoryKnowledgeRepository(), knowledgeServiceClient, knowledgeWorkflowGateway, platformEventService);
    }

    CatalogService(
        CatalogRepository repository,
        com.lynxus.platform.knowledge.KnowledgeRepository knowledgeRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService
    ) {
        this.repository = repository;
        this.platformEventService = platformEventService;
        this.knowledgeService = new KnowledgeService(knowledgeRepository, repository, knowledgeServiceClient, knowledgeWorkflowGateway, platformEventService);
    }

    public KnowledgeService knowledgeService() {
        return knowledgeService;
    }

    public CatalogSummaryDto summary() {
        ensureLoaded();
        return new CatalogSummaryDto(
            listDomains(),
            listScenarios(),
            listAssistants(),
            listAgents(),
            listResources(),
            listKnowledgeBases(),
            resourceCenter(),
            resourceBlueprints()
        );
    }

    public ObjectReferenceAnalysisDto objectReferences(String objectType, String objectId) {
        ensureLoaded();
        String normalizedObjectType = normalizeReferenceObjectType(objectType);
        return switch (normalizedObjectType) {
            case "DOMAIN" -> analyzeDomainReferences(findDomain(objectId));
            case "SCENARIO" -> analyzeScenarioReferences(findScenario(objectId));
            case "ASSISTANT" -> analyzeAssistantReferences(findAssistant(objectId));
            case "PLAYBOOK" -> analyzePlaybookReferences(findPlaybook(objectId));
            case "AGENT" -> analyzeAgentReferences(findAgent(objectId));
            case "RESOURCE" -> analyzeResourceReferences(toResourceView(findResource(objectId)));
            case "KNOWLEDGE_BASE" -> analyzeKnowledgeBaseReferences(getKnowledgeBase(objectId));
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
    }

    public DeletionImpactPreviewDto deletionPreview(String objectType, String objectId) {
        ensureLoaded();
        String normalizedObjectType = normalizeReferenceObjectType(objectType);
        ObjectReferenceAnalysisDto analysis = objectReferences(normalizedObjectType, objectId);
        List<ObjectReferenceRelationDto> blockers = relationsByImpactLevel(analysis, "BLOCKS_DELETION");
        List<ObjectReferenceRelationDto> advisories = relationsByImpactLevel(analysis, "ADVISORY");
        return new DeletionImpactPreviewDto(
            analysis.objectType(),
            analysis.objectId(),
            analysis.objectName(),
            blockers.isEmpty(),
            blockers,
            advisories,
            buildCascadeDeletes(normalizedObjectType, objectId)
        );
    }

    public List<BusinessDomainDto> listDomains() {
        ensureLoaded();
        return domains.stream()
            .sorted(Comparator.comparing(BusinessDomainDto::name))
            .map(this::toDomainView)
            .toList();
    }

    public BusinessDomainDto getDomain(String domainId) {
        ensureLoaded();
        return toDomainView(findDomain(domainId));
    }

    public BusinessDomainDto createDomain(CreateDomainRequest request) {
        ensureLoaded();
        String name = requireText(request.name(), "domain.name");
        ensureUniqueDomainName(name, null);
        BusinessDomainDto domain = new BusinessDomainDto(
            nextId("domain"),
            name,
            normalizeOptionalText(request.description()),
            List.of(),
            List.of(),
            List.of()
        );
        domains.add(domain);
        persistState();
        recordCatalogEvent("DOMAIN_CREATED", PlatformAggregateType.DOMAIN, domain.id(), Map.of("name", domain.name()));
        return toDomainView(domain);
    }

    public BusinessDomainDto updateDomain(String domainId, UpdateDomainRequest request) {
        ensureLoaded();
        BusinessDomainDto existing = findDomain(domainId);
        String name = requireText(request.name(), "domain.name");
        ensureUniqueDomainName(name, existing.id());
        BusinessDomainDto updated = new BusinessDomainDto(
            existing.id(),
            name,
            normalizeOptionalText(request.description()),
            existing.scenarios(),
            existing.resources(),
            existing.knowledgeBases()
        );
        replace(domains, BusinessDomainDto::id, updated);
        persistState();
        recordCatalogEvent("DOMAIN_UPDATED", PlatformAggregateType.DOMAIN, updated.id(), Map.of("name", updated.name()));
        return toDomainView(updated);
    }

    public BusinessDomainDto deleteDomain(String domainId) {
        ensureLoaded();
        BusinessDomainDto existing = findDomain(domainId);
        String blocker = findObjectDeletionBlocker("DOMAIN", domainId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        domains.removeIf(item -> item.id().equals(domainId));
        persistState();
        recordCatalogEvent(
            "DOMAIN_DELETED",
            PlatformAggregateType.DOMAIN,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return toDomainView(existing);
    }

    public List<ScenarioDto> listScenarios() {
        ensureLoaded();
        return scenarios.stream()
            .sorted(Comparator.comparing(ScenarioDto::name))
            .map(this::toScenarioView)
            .toList();
    }

    public ScenarioDto getScenario(String scenarioId) {
        ensureLoaded();
        return toScenarioView(findScenario(scenarioId));
    }

    public ScenarioDto createScenario(CreateScenarioRequest request) {
        ensureLoaded();
        String domainId = requireText(request.domainId(), "scenario.domainId");
        findDomain(domainId);
        String name = requireText(request.name(), "scenario.name");
        ensureUniqueScenarioName(domainId, name, null);
        ScenarioDto scenario = new ScenarioDto(
            nextId("scenario"),
            domainId,
            name,
            requireText(request.goal(), "scenario.goal"),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of()
        );
        scenarios.add(scenario);
        persistState();
        recordCatalogEvent("SCENARIO_CREATED", PlatformAggregateType.SCENARIO, scenario.id(), Map.of("name", scenario.name()));
        return toScenarioView(scenario);
    }

    public ScenarioDto updateScenario(String scenarioId, UpdateScenarioRequest request) {
        ensureLoaded();
        ScenarioDto existing = findScenario(scenarioId);
        String name = requireText(request.name(), "scenario.name");
        ensureUniqueScenarioName(existing.domainId(), name, existing.id());
        ScenarioDto updated = new ScenarioDto(
            existing.id(),
            existing.domainId(),
            name,
            requireText(request.goal(), "scenario.goal"),
            existing.version(),
            existing.assistants()
        );
        replace(scenarios, ScenarioDto::id, updated);
        persistState();
        recordCatalogEvent("SCENARIO_UPDATED", PlatformAggregateType.SCENARIO, updated.id(), Map.of("name", updated.name()));
        return toScenarioView(updated);
    }

    public ScenarioDto deleteScenario(String scenarioId) {
        ensureLoaded();
        ScenarioDto existing = findScenario(scenarioId);
        String blocker = findObjectDeletionBlocker("SCENARIO", scenarioId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        scenarios.removeIf(item -> item.id().equals(scenarioId));
        persistState();
        recordCatalogEvent(
            "SCENARIO_DELETED",
            PlatformAggregateType.SCENARIO,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return toScenarioView(existing);
    }

    public AssistantDto createAssistant(CreateAssistantRequest request) {
        ensureLoaded();
        AssistantModelPolicyDto normalizedModelPolicy = normalizeAssistantModelPolicy(request.modelPolicy());
        validateAssistantModelPolicy(normalizedModelPolicy);
        validatePrivacyModelResourceId(request.privacyModelResourceId(), "assistant privacyModelResourceId");
        AssistantDto assistant = new AssistantDto(
            nextId("assistant"),
            request.scenarioId(),
            request.name(),
            request.description(),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of(),
            List.of(),
            null,
            List.of(),
            normalizePrimaryAgentId(request.primaryAgentId()),
            normalizeAssistantOwnerPolicy(request.ownerPolicy()),
            normalizeAssistantSessionPolicy(request.sessionPolicy()),
            normalizeAssistantReplyPolicy(request.replyPolicy()),
            normalizeAssistantPlaybookPolicy(request.playbookPolicy()),
            normalizedModelPolicy,
            normalizeOptionalText(request.privacyModelResourceId()),
            request.privacyMappingEnabled(),
            normalizeKnowledgeAccessPolicy(request.knowledgeAccessPolicy()),
            normalizeMemoryPolicy(request.memoryPolicy())
        );
        assistants.add(assistant);
        persistState();
        recordCatalogEvent("ASSISTANT_CREATED", PlatformAggregateType.ASSISTANT, assistant.id(), Map.of("name", assistant.name()));
        return toAssistantView(assistant);
    }

    public AssistantDto updateAssistant(String assistantId, UpdateAssistantRequest request) {
        ensureLoaded();
        AssistantDto existing = findAssistant(assistantId);
        VersionStatus effectiveStatus = request.status() == null ? existing.version().status() : request.status();
        String primaryAgentId = request.primaryAgentId() == null
            ? existing.primaryAgentId()
            : normalizePrimaryAgentId(request.primaryAgentId());
        AssistantOwnerPolicyDto ownerPolicy = request.ownerPolicy() == null
            ? existing.ownerPolicy()
            : normalizeAssistantOwnerPolicy(request.ownerPolicy());
        AssistantSessionPolicyDto sessionPolicy = request.sessionPolicy() == null
            ? existing.sessionPolicy()
            : normalizeAssistantSessionPolicy(request.sessionPolicy());
        AssistantReplyPolicyDto replyPolicy = request.replyPolicy() == null
            ? existing.replyPolicy()
            : normalizeAssistantReplyPolicy(request.replyPolicy());
        AssistantPlaybookPolicyDto playbookPolicy = request.playbookPolicy() == null
            ? existing.playbookPolicy()
            : normalizeAssistantPlaybookPolicy(request.playbookPolicy());
        AssistantModelPolicyDto normalizedModelPolicy = request.modelPolicy() == null
            ? existing.modelPolicy()
            : normalizeAssistantModelPolicy(request.modelPolicy());
        String privacyModelResourceId = normalizeOptionalText(request.privacyModelResourceId());
        boolean privacyMappingEnabled = request.privacyMappingEnabled();
        KnowledgeAccessPolicyDto knowledgeAccessPolicy = request.knowledgeAccessPolicy() == null
            ? existing.knowledgeAccessPolicy()
            : normalizeKnowledgeAccessPolicy(request.knowledgeAccessPolicy());
        MemoryPolicyDto memoryPolicy = request.memoryPolicy() == null
            ? existing.memoryPolicy()
            : normalizeMemoryPolicy(request.memoryPolicy());
        validateAssistantModelPolicy(normalizedModelPolicy);
        validatePrivacyModelResourceId(privacyModelResourceId, "assistant privacyModelResourceId");
        VersionDto version = new VersionDto(
            effectiveStatus == VersionStatus.PUBLISHED ? nextAssistantReleaseVersion(existing.id()) : existing.version().version(),
            effectiveStatus,
            Instant.now()
        );
        AssistantDto updated = new AssistantDto(
            existing.id(),
            existing.scenarioId(),
            request.name(),
            request.description(),
            version,
            existing.agents(),
            existing.playbooks(),
            existing.currentRelease(),
            existing.releases(),
            primaryAgentId,
            ownerPolicy,
            sessionPolicy,
            replyPolicy,
            playbookPolicy,
            normalizedModelPolicy,
            privacyModelResourceId,
            privacyMappingEnabled,
            knowledgeAccessPolicy,
            memoryPolicy
        );
        validateAssistantOwnerConfiguration(updated);
        if (effectiveStatus == VersionStatus.PUBLISHED) {
            ensureAssistantReadyForPublication(updated);
        }
        AssistantReleaseDto publishedRelease = null;
        replace(assistants, AssistantDto::id, updated);
        if (effectiveStatus == VersionStatus.PUBLISHED) {
            publishedRelease = createAssistantRelease(updated.id(), version.version(), VersionStatus.PUBLISHED);
        }
        persistState();
        recordCatalogEvent("ASSISTANT_UPDATED", PlatformAggregateType.ASSISTANT, updated.id(), Map.of("name", updated.name()));
        if (publishedRelease != null) {
            recordCatalogEvent(
                "ASSISTANT_RELEASE_PUBLISHED",
                PlatformAggregateType.ASSISTANT,
                updated.id(),
                Map.of(
                    "name", updated.name(),
                    "releaseId", publishedRelease.id(),
                    "version", publishedRelease.releaseVersion(),
                    "status", publishedRelease.status().name()
                )
            );
        }
        return toAssistantView(updated);
    }

    public AssistantDto deleteAssistant(String assistantId) {
        ensureLoaded();
        AssistantDto existing = findAssistant(assistantId);
        String blocker = findObjectDeletionBlocker("ASSISTANT", assistantId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }

        AssistantDto deleted = toAssistantView(existing);
        assistants.removeIf(item -> item.id().equals(assistantId));
        assistantReleases.remove(assistantId);
        persistState();
        recordCatalogEvent(
            "ASSISTANT_DELETED",
            PlatformAggregateType.ASSISTANT,
            deleted.id(),
            Map.of("name", deleted.name(), "deletedObjectId", deleted.id(), "deletedObjectName", deleted.name())
        );
        return deleted;
    }

    public List<AssistantDto> listAssistants() {
        ensureLoaded();
        return assistants.stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(this::toAssistantView)
            .toList();
    }

    public AssistantDto getAssistant(String assistantId) {
        ensureLoaded();
        return toAssistantView(findAssistant(assistantId));
    }

    public AgentDto createAgent(CreateAgentRequest request) {
        ensureLoaded();
        AgentDto agent = new AgentDto(
            nextId("agent"),
            request.assistantId(),
            request.name(),
            request.role(),
            request.responsibility(),
            normalizeAgentExecutionPolicy(request.executionPolicy()),
            request.canOwnSession(),
            normalizeAllowedActions(request.allowedActions()),
            request.switchableOwnerAgentIds() == null ? List.of() : List.copyOf(request.switchableOwnerAgentIds()),
            request.playbookIds() == null ? List.of() : List.copyOf(request.playbookIds())
        );
        validatePrivacyModelResourceId(agent.executionPolicy().privacyModelResourceId(), "agent privacyModelResourceId");
        validateAgentPlaybookReferences(agent.assistantId(), agent.playbookIds());
        agents.add(agent);
        AssistantDto assistant = findAssistant(request.assistantId());
        if (assistant.primaryAgentId() != null && !assistant.primaryAgentId().isBlank()) {
            validateAssistantOwnerConfiguration(assistant);
        }
        persistState();
        recordCatalogEvent("AGENT_CREATED", PlatformAggregateType.AGENT, agent.id(), Map.of("name", agent.name()));
        return agent;
    }

    public AgentDto updateAgent(String agentId, UpdateAgentRequest request) {
        ensureLoaded();
        AgentDto existing = findAgent(agentId);
        AgentDto updated = new AgentDto(
            existing.id(),
            existing.assistantId(),
            request.name(),
            request.role(),
            request.responsibility(),
            normalizeAgentExecutionPolicy(request.executionPolicy()),
            request.canOwnSession(),
            normalizeAllowedActions(request.allowedActions()),
            request.switchableOwnerAgentIds() == null ? List.of() : List.copyOf(request.switchableOwnerAgentIds()),
            request.playbookIds() == null ? List.of() : List.copyOf(request.playbookIds())
        );
        validatePrivacyModelResourceId(updated.executionPolicy().privacyModelResourceId(), "agent privacyModelResourceId");
        validateAgentPlaybookReferences(updated.assistantId(), updated.playbookIds());
        replace(agents, AgentDto::id, updated);
        AssistantDto assistant = findAssistant(existing.assistantId());
        if (assistant.primaryAgentId() != null && !assistant.primaryAgentId().isBlank()) {
            validateAssistantOwnerConfiguration(assistant);
        }
        persistState();
        recordCatalogEvent("AGENT_UPDATED", PlatformAggregateType.AGENT, updated.id(), Map.of("name", updated.name()));
        return updated;
    }

    public AgentDto deleteAgent(String agentId) {
        ensureLoaded();
        AgentDto existing = findAgent(agentId);
        String blocker = findObjectDeletionBlocker("AGENT", agentId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        agents.removeIf(item -> item.id().equals(agentId));
        clearDeletedPrimaryAgent(existing.assistantId(), agentId);
        persistState();
        recordCatalogEvent(
            "AGENT_DELETED",
            PlatformAggregateType.AGENT,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return existing;
    }

    public List<AgentDto> listAgents() {
        ensureLoaded();
        return agents.stream().sorted(Comparator.comparing(AgentDto::name)).toList();
    }

    public AgentDto getAgent(String agentId) {
        ensureLoaded();
        return findAgent(agentId);
    }

    public List<PlaybookDto> listPlaybooks() {
        ensureLoaded();
        return playbooks.stream()
            .sorted(Comparator.comparing(PlaybookDto::name))
            .map(this::normalizePlaybook)
            .toList();
    }

    public PlaybookDto getPlaybook(String playbookId) {
        ensureLoaded();
        return normalizePlaybook(findPlaybook(playbookId));
    }

    public PlaybookDto createPlaybook(CreatePlaybookRequest request) {
        ensureLoaded();
        findAssistant(request.assistantId());
        PlaybookDto playbook = new PlaybookDto(
            nextId("playbook"),
            request.assistantId(),
            requireText(request.name(), "playbook.name"),
            normalizeOptionalText(request.description()),
            normalizeOptionalText(request.inputSchema()),
            normalizeOptionalText(request.resultSchema()),
            normalizePlaybookExecutionPolicy(request.executionPolicy()),
            request.allowHumanTask(),
            request.allowExternalInteraction(),
            requireText(request.entryNodeKey(), "playbook.entryNodeKey"),
            request.nodes() == null ? List.of() : request.nodes().stream().map(this::normalizePlaybookNode).toList(),
            request.edges() == null ? List.of() : request.edges().stream().map(this::normalizePlaybookEdge).toList()
        );
        validatePlaybookDefinition(playbook);
        playbooks.add(playbook);
        persistState();
        recordCatalogEvent("PLAYBOOK_CREATED", PlatformAggregateType.PLAYBOOK, playbook.id(), Map.of("name", playbook.name()));
        return normalizePlaybook(playbook);
    }

    public PlaybookDto updatePlaybook(String playbookId, UpdatePlaybookRequest request) {
        ensureLoaded();
        PlaybookDto existing = findPlaybook(playbookId);
        PlaybookDto updated = new PlaybookDto(
            existing.id(),
            existing.assistantId(),
            requireText(request.name(), "playbook.name"),
            normalizeOptionalText(request.description()),
            normalizeOptionalText(request.inputSchema()),
            normalizeOptionalText(request.resultSchema()),
            normalizePlaybookExecutionPolicy(request.executionPolicy()),
            request.allowHumanTask(),
            request.allowExternalInteraction(),
            requireText(request.entryNodeKey(), "playbook.entryNodeKey"),
            request.nodes() == null ? List.of() : request.nodes().stream().map(this::normalizePlaybookNode).toList(),
            request.edges() == null ? List.of() : request.edges().stream().map(this::normalizePlaybookEdge).toList()
        );
        validatePlaybookDefinition(updated);
        replace(playbooks, PlaybookDto::id, updated);
        persistState();
        recordCatalogEvent("PLAYBOOK_UPDATED", PlatformAggregateType.PLAYBOOK, updated.id(), Map.of("name", updated.name()));
        return normalizePlaybook(updated);
    }

    public PlaybookDto deletePlaybook(String playbookId) {
        ensureLoaded();
        PlaybookDto existing = findPlaybook(playbookId);
        String blocker = findObjectDeletionBlocker("PLAYBOOK", playbookId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        playbooks.removeIf(item -> item.id().equals(playbookId));
        persistState();
        recordCatalogEvent(
            "PLAYBOOK_DELETED",
            PlatformAggregateType.PLAYBOOK,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return normalizePlaybook(existing);
    }

    public ResourceDto createResource(CreateResourceRequest request) {
        ensureLoaded();
        String domainId = requireText(request.domainId(), "resource.domainId");
        findDomain(domainId);
        validateResourceOwner(domainId, request.ownerType(), request.ownerId());
        String resourceId = nextId("resource");
        ResourceDto resource = new ResourceDto(
            resourceId,
            domainId,
            requireText(request.name(), "resource.name"),
            request.type(),
            request.shareScope(),
            request.ownerType(),
            request.ownerId(),
            normalizeOptionalText(request.summary()),
            normalizeOptionalText(request.steward()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            null,
            null,
            List.of()
        );
        resources.add(resource);
        createResourceVersion(
            resourceId,
            request.initialVersion() == null
                ? new CreateResourceVersionRequest("初始版本", VersionStatus.DRAFT, defaultConfiguration(resource.type()))
                : normalizeInitialVersionRequest(resource.type(), request.initialVersion())
        );
        recordCatalogEvent("RESOURCE_CREATED", PlatformAggregateType.RESOURCE, resource.id(), Map.of("name", resource.name()));
        return toResourceView(resource);
    }

    public List<ResourceVersionDto> listResourceVersions(String resourceId) {
        ensureLoaded();
        findResource(resourceId);
        return versionsFor(resourceId);
    }

    public ResourceDto updateResource(String resourceId, UpdateResourceRequest request) {
        ensureLoaded();
        ResourceDto existing = findResource(resourceId);
        String name = requireText(request.name(), "resource.name");
        validateResourceOwner(existing.domainId(), request.ownerType(), request.ownerId());
        ResourceDto updated = new ResourceDto(
            existing.id(),
            existing.domainId(),
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
        replace(resources, ResourceDto::id, updated);
        persistState();
        recordCatalogEvent("RESOURCE_UPDATED", PlatformAggregateType.RESOURCE, updated.id(), Map.of("name", updated.name()));
        return toResourceView(updated);
    }

    public ResourceVersionDto createResourceVersion(String resourceId, CreateResourceVersionRequest request) {
        ensureLoaded();
        ResourceDto resource = findResource(resourceId);
        VersionStatus status = request.status() == null ? VersionStatus.DRAFT : request.status();
        ResourceVersionConfigurationDto normalizedConfiguration = normalizeConfiguration(resource.type(), request.configuration());
        String configDigest = generateConfigDigest(normalizedConfiguration);
        if (status == VersionStatus.PUBLISHED) {
            validateVersionReadyForActivation(resource.type(), normalizedConfiguration);
        }
        List<StoredResourceVersion> existingVersions = storedVersionsFor(resourceId).stream()
            .map(version -> status == VersionStatus.PUBLISHED && version.status() == VersionStatus.PUBLISHED
                ? new StoredResourceVersion(
                    version.id(),
                    version.resourceId(),
                    version.version(),
                    VersionStatus.DRAFT,
                    version.summary(),
                    version.configDigest(),
                    version.createdAt(),
                    null,
                    version.configuration()
                )
                : version)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
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
        resourceVersions.put(resourceId, existingVersions);
        persistState();
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
        ensureLoaded();
        ResourceDto resource = findResource(resourceId);
        StoredResourceVersion existing = storedVersionsFor(resourceId).stream()
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

        List<StoredResourceVersion> updatedVersions = storedVersionsFor(resourceId).stream()
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
                if (targetStatus == VersionStatus.PUBLISHED && version.status() == VersionStatus.PUBLISHED) {
                    return new StoredResourceVersion(
                        version.id(),
                        version.resourceId(),
                        version.version(),
                        VersionStatus.DRAFT,
                        version.summary(),
                        version.configDigest(),
                        version.createdAt(),
                        null,
                        version.configuration()
                    );
                }
                return version;
            })
            .toList();
        resourceVersions.put(resourceId, updatedVersions);
        persistState();
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
    }

    public ResourceVersionDto publishResourceVersion(String resourceId, String versionId) {
        ensureLoaded();
        ResourceDto resource = findResource(resourceId);
        ResourceVersionDto targetVersion = findResourceVersion(resourceId, versionId);
        validateVersionReadyForActivation(resource.type(), targetVersion.configuration());
        List<StoredResourceVersion> updatedVersions = storedVersionsFor(resourceId).stream()
            .map(version -> new StoredResourceVersion(
                version.id(),
                version.resourceId(),
                version.version(),
                version.id().equals(versionId) ? VersionStatus.PUBLISHED : VersionStatus.DRAFT,
                version.summary(),
                version.configDigest(),
                version.createdAt(),
                version.id().equals(versionId) ? Instant.now() : null,
                version.configuration()
            ))
            .toList();
        resourceVersions.put(resourceId, updatedVersions);
        persistState();
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
    }

    public ResourceVersionDto deleteResourceVersion(String resourceId, String versionId) {
        ensureLoaded();
        ResourceDto resource = toResourceView(findResource(resourceId));
        ResourceVersionDto version = findResourceVersion(resourceId, versionId);
        if (resource.effectiveVersion() != null && resource.effectiveVersion().id().equals(versionId)) {
            throw new IllegalStateException("resource effective version cannot be deleted: " + resourceId + "/" + versionId);
        }
        if (versionsFor(resourceId).size() <= 1) {
            throw new IllegalStateException("resource must keep at least one version: " + resourceId);
        }

        String versionPinBlocker = findResourceVersionDeletionBlocker(resourceId, versionId);
        if (versionPinBlocker != null) {
            throw new IllegalStateException(versionPinBlocker);
        }

        List<StoredResourceVersion> updatedVersions = storedVersionsFor(resourceId).stream()
            .filter(item -> !item.id().equals(versionId))
            .toList();
        resourceVersions.put(resourceId, updatedVersions);
        persistState();
        recordCatalogEvent(
            "RESOURCE_VERSION_DELETED",
            PlatformAggregateType.RESOURCE,
            resource.id(),
            Map.of("name", resource.name(), "versionId", version.id(), "version", version.version(), "status", version.status().name())
        );
        return version;
    }

    public List<ResourceDto> listResources() {
        ensureLoaded();
        return resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .toList();
    }

    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return knowledgeService.listKnowledgeBases();
    }

    public KnowledgeBaseDto getKnowledgeBase(String knowledgeBaseId) {
        return knowledgeService.getKnowledgeBase(knowledgeBaseId);
    }

    public KnowledgeBaseDto createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        return knowledgeService.createKnowledgeBase(request);
    }

    public KnowledgeBaseDto updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        return knowledgeService.updateKnowledgeBase(knowledgeBaseId, request);
    }

    public KnowledgeBaseDto deleteKnowledgeBase(String knowledgeBaseId) {
        String blocker = findObjectDeletionBlocker("KNOWLEDGE_BASE", knowledgeBaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        return knowledgeService.deleteKnowledgeBaseUnchecked(knowledgeBaseId);
    }

    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return knowledgeService.listKnowledgeReleases(knowledgeBaseId);
    }

    public KnowledgeReleaseDto createKnowledgeRelease(String knowledgeBaseId, CreateKnowledgeReleaseRequest request) {
        return knowledgeService.createKnowledgeRelease(knowledgeBaseId, request);
    }

    public KnowledgeReleaseDto publishKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return knowledgeService.publishKnowledgeRelease(knowledgeBaseId, releaseId);
    }

    public KnowledgeReleaseDto deleteKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return knowledgeService.deleteKnowledgeRelease(knowledgeBaseId, releaseId);
    }

    public List<KnowledgeReferenceDto> listKnowledgeReferences(String knowledgeBaseId) {
        return toKnowledgeReferences(objectReferences("KNOWLEDGE_BASE", knowledgeBaseId));
    }

    public ResourceDto deleteResource(String resourceId) {
        ensureLoaded();
        ResourceDto deleted = toResourceView(findResource(resourceId));
        String referenceBlocker = findObjectDeletionBlocker("RESOURCE", resourceId);
        if (referenceBlocker != null) {
            throw new IllegalStateException(referenceBlocker);
        }

        resources.removeIf(item -> item.id().equals(resourceId));
        resourceVersions.remove(resourceId);
        persistState();
        recordCatalogEvent(
            "RESOURCE_DELETED",
            PlatformAggregateType.RESOURCE,
            deleted.id(),
            Map.of("name", deleted.name(), "deletedObjectId", deleted.id(), "deletedObjectName", deleted.name())
        );
        return deleted;
    }

    public KnowledgeUploadSessionDto createKnowledgeUploadSession(String knowledgeBaseId) {
        return knowledgeService.createUploadSession(knowledgeBaseId);
    }

    public KnowledgeUploadCompletionDto completeKnowledgeUpload(
        String knowledgeBaseId,
        String uploadSessionId,
        String fileName,
        String contentType,
        byte[] payload
    ) {
        return knowledgeService.completeUpload(knowledgeBaseId, uploadSessionId, fileName, contentType, payload);
    }

    public KnowledgeUploadCompletionDto importKnowledgeUrl(String knowledgeBaseId, CreateKnowledgeUrlImportRequest request) {
        return knowledgeService.importUrl(knowledgeBaseId, request);
    }

    public List<KnowledgeFileDto> listKnowledgeFiles(String knowledgeBaseId) {
        return knowledgeService.listFiles(knowledgeBaseId);
    }

    public List<KnowledgeImportJobDto> listKnowledgeImportJobs(String knowledgeBaseId) {
        return knowledgeService.listImportJobs(knowledgeBaseId);
    }

    public List<KnowledgeDocumentDto> listKnowledgeDocuments(String knowledgeBaseId) {
        return knowledgeService.listDocuments(knowledgeBaseId);
    }

    public KnowledgeIndexSnapshotDto createKnowledgeIndexSnapshot(String knowledgeBaseId, CreateKnowledgeIndexSnapshotRequest request) {
        return knowledgeService.createIndexSnapshot(knowledgeBaseId, request);
    }

    public List<KnowledgeIndexSnapshotDto> listKnowledgeIndexSnapshots(String knowledgeBaseId) {
        return knowledgeService.listIndexSnapshots(knowledgeBaseId);
    }

    public ResourceCenterDto resourceCenter() {
        ensureLoaded();
        List<ResourceReferenceDto> references = resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .flatMap(resource -> toResourceReferences(resource, analyzeResourceReferences(resource)).stream())
            .toList();
        long domainShared = resources.stream().filter(item -> item.shareScope() == ShareScope.DOMAIN_SHARED).count();
        long privateCount = resources.stream().filter(item -> item.shareScope() == ShareScope.PRIVATE).count();
        return new ResourceCenterDto(resources.size(), Math.toIntExact(domainShared), Math.toIntExact(privateCount), references);
    }

    public List<ResourceBlueprintDto> resourceBlueprints() {
        return List.of(
            new ResourceBlueprintDto(
                ResourceType.TOOL,
                "Tool",
                "承载 agent 可调用的业务能力，并通过 provider 定义其 HTTP 或 MCP 实现方式。",
                List.of("操作定义", "Provider 类型", "鉴权方式", "超时设置", "重试策略", "HTTP / MCP Provider 配置"),
                defaultConfiguration(ResourceType.TOOL)
            ),
            new ResourceBlueprintDto(
                ResourceType.LLM_MODEL,
                "LLM 模型",
                "承载多供应商模型连接、默认参数和鉴权入口，用于助手和智能体的真实模型调用。",
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
    }

    private synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        restore(repository.load());
        initialized = true;
        persistState(); // backfill reference projection tables
    }

    private void recordCatalogEvent(String eventType, PlatformAggregateType aggregateType, String aggregateId, Map<String, Object> payload) {
        platformEventService.recordControlEvent(eventType, aggregateType, aggregateId, payload);
    }

    private BusinessDomainDto toDomainView(BusinessDomainDto domain) {
        List<ScenarioDto> domainScenarios = scenarios.stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ScenarioDto::name))
            .map(this::toScenarioView)
            .toList();
        List<ResourceDto> domainResources = resources.stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .toList();
        List<KnowledgeBaseDto> domainKnowledgeBases = knowledgeService.listKnowledgeBasesByDomain(domain.id());
        return new BusinessDomainDto(domain.id(), domain.name(), domain.description(), domainScenarios, domainResources, domainKnowledgeBases);
    }

    private ScenarioDto toScenarioView(ScenarioDto scenario) {
        List<AssistantDto> scenarioAssistants = assistants.stream()
            .filter(item -> item.scenarioId().equals(scenario.id()))
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(this::toAssistantView)
            .toList();
        return new ScenarioDto(scenario.id(), scenario.domainId(), scenario.name(), scenario.goal(), scenario.version(), scenarioAssistants);
    }

    private AssistantDto toAssistantView(AssistantDto assistant) {
        List<AgentDto> assistantAgents = orderAgentsForAssistant(assistant.id());
        List<AssistantReleaseDto> releases = assistantReleases.getOrDefault(assistant.id(), List.of()).stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .toList();
        return new AssistantDto(
            assistant.id(),
            assistant.scenarioId(),
            assistant.name(),
            assistant.description(),
            assistant.version(),
            assistantAgents,
            playbooksForAssistant(assistant.id()),
            releases.isEmpty() ? null : releases.getFirst(),
            releases,
            normalizePrimaryAgentId(assistant.primaryAgentId()),
            normalizeAssistantOwnerPolicy(assistant.ownerPolicy()),
            normalizeAssistantSessionPolicy(assistant.sessionPolicy()),
            normalizeAssistantReplyPolicy(assistant.replyPolicy()),
            normalizeAssistantPlaybookPolicy(assistant.playbookPolicy()),
            normalizeAssistantModelPolicy(assistant.modelPolicy()),
            normalizeOptionalText(assistant.privacyModelResourceId()),
            assistant.privacyMappingEnabled(),
            normalizeKnowledgeAccessPolicy(assistant.knowledgeAccessPolicy()),
            normalizeMemoryPolicy(assistant.memoryPolicy())
        );
    }

    private ResourceDto toResourceView(ResourceDto resource) {
        List<ResourceVersionDto> versions = versionsFor(resource.id());
        return new ResourceDto(
            resource.id(),
            resource.domainId(),
            resource.name(),
            resource.type(),
            resource.shareScope(),
            resource.ownerType(),
            resource.ownerId(),
            resource.summary(),
            resource.steward(),
            resource.tags(),
            versions.isEmpty() ? null : versions.getLast(),
            versions.stream().filter(item -> item.status() == VersionStatus.PUBLISHED).reduce((__, item) -> item).orElse(null),
            versions
        );
    }

    private List<AgentDto> orderAgentsForAssistant(String assistantId) {
        return agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();
    }

    private AssistantReleaseDto createAssistantRelease(String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        DefaultModelBindingDto defaultModelBinding = resolveDefaultModelBinding(assistant);
        DefaultModelBindingDto privacyModelBinding = resolvePrivacyModelBinding(assistant);
        captureEffectiveResource(snapshotMap, assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");
        captureEffectiveResource(snapshotMap, assistant.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL");
        KnowledgeBindingSnapshotDto assistantKnowledgeBinding = resolveAssistantKnowledgeBinding(assistant);

        List<AssistantReleaseAgentDto> releaseAgents = new ArrayList<>();
        for (AgentDto agent : orderAgentsForAssistant(assistantId)) {
            captureEffectiveResource(snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());
            String effectivePrivacyModelResourceId = resolveEffectivePrivacyModelResourceId(assistant, agent);
            boolean effectivePrivacyMappingEnabled = resolveEffectivePrivacyMappingEnabled(assistant, agent);
            DefaultModelBindingDto effectivePrivacyModelBinding = effectivePrivacyMappingEnabled
                ? resolvePrivacyModelBinding(assistant, agent)
                : null;
            captureEffectiveResource(snapshotMap, effectivePrivacyModelResourceId, agent.name() + "_PRIVACY_MODEL");

            List<String> skillResourceVersionIds = new ArrayList<>();
            for (String skillResourceId : agent.executionPolicy().skillResourceIds()) {
                ResourceDto skillResource = toResourceView(findResource(skillResourceId));
                if (skillResource.type() != ResourceType.SKILL) {
                    throw new IllegalStateException("agent skill must reference SKILL resource: " + agent.name() + " -> " + skillResource.name());
                }
                ResourceVersionDto version = effectiveVersion(skillResource);
                skillResourceVersionIds.add(version.id());
                mergeReleaseResource(snapshotMap, skillResource, version, agent.name());
            }
            List<String> toolResourceVersionIds = new ArrayList<>();
            for (String toolResourceId : agent.executionPolicy().toolResourceIds()) {
                ResourceDto toolResource = toResourceView(findResource(toolResourceId));
                if (toolResource.type() != ResourceType.TOOL) {
                    throw new IllegalStateException("agent tool must reference TOOL resource: " + agent.name() + " -> " + toolResource.name());
                }
                ResourceVersionDto version = effectiveVersion(toolResource);
                toolResourceVersionIds.add(version.id());
                mergeReleaseResource(snapshotMap, toolResource, version, agent.name());
            }
            releaseAgents.add(new AssistantReleaseAgentDto(
                agent.id(),
                agent.name(),
                agent.role(),
                agent.responsibility(),
                agent.executionPolicy(),
                resolveAgentKnowledgeBinding(assistantKnowledgeBinding, agent),
                effectivePrivacyModelBinding,
                effectivePrivacyMappingEnabled,
                agent.canOwnSession(),
                agent.allowedActions(),
                agent.switchableOwnerAgentIds(),
                agent.playbookIds(),
                List.copyOf(skillResourceVersionIds),
                List.copyOf(toolResourceVersionIds)
            ));
        }

        AssistantReleaseDto release = new AssistantReleaseDto(
            nextId("assistant-release"),
            assistant.id(),
            releaseVersion,
            status,
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null,
            assistantKnowledgeBinding,
            defaultModelBinding,
            privacyModelBinding,
            assistant.privacyMappingEnabled(),
            List.copyOf(snapshotMap.values()),
            List.copyOf(releaseAgents),
            playbooksForAssistant(assistantId),
            assistant.primaryAgentId(),
            assistant.ownerPolicy(),
            assistant.sessionPolicy(),
            assistant.replyPolicy(),
            assistant.playbookPolicy(),
            assistant.modelPolicy(),
            assistant.knowledgeAccessPolicy(),
            assistant.memoryPolicy()
        );
        List<AssistantReleaseDto> releases = new ArrayList<>(assistantReleases.getOrDefault(assistantId, List.of()));
        releases.add(release);
        assistantReleases.put(assistantId, releases);
        return release;
    }

    private KnowledgeBindingSnapshotDto resolveAssistantKnowledgeBinding(AssistantDto assistant) {
        if (!assistant.knowledgeAccessPolicy().enabled() || assistant.knowledgeAccessPolicy().knowledgeBaseId() == null || assistant.knowledgeAccessPolicy().knowledgeBaseId().isBlank()) {
            return null;
        }
        return knowledgeService.resolveKnowledgeBinding(assistant.knowledgeAccessPolicy().knowledgeBaseId());
    }

    private KnowledgeBindingSnapshotDto resolveAgentKnowledgeBinding(KnowledgeBindingSnapshotDto assistantKnowledgeBinding, AgentDto agent) {
        if (!agent.executionPolicy().knowledgeEnabled()) {
            return null;
        }
        if (agent.executionPolicy().inheritAssistantKnowledge()) {
            if (assistantKnowledgeBinding == null) {
                throw new IllegalStateException("assistant knowledge binding must exist when inheritAssistantKnowledge=true and knowledgeEnabled=true");
            }
            return assistantKnowledgeBinding;
        }
        if (agent.executionPolicy().knowledgeBaseId() == null || agent.executionPolicy().knowledgeBaseId().isBlank()) {
            throw new IllegalStateException("agent knowledgeBaseId must be configured when knowledgeEnabled=true and inheritAssistantKnowledge=false");
        }
        return knowledgeService.resolveKnowledgeBinding(agent.executionPolicy().knowledgeBaseId());
    }

    private void captureEffectiveResource(Map<String, AssistantReleaseResourceDto> snapshotMap, String resourceId, String boundAgent) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = toResourceView(findResource(resourceId));
        ResourceVersionDto version = effectiveVersion(resource);
        mergeReleaseResource(snapshotMap, resource, version, boundAgent);
    }

    private void mergeReleaseResource(
        Map<String, AssistantReleaseResourceDto> snapshotMap,
        ResourceDto resource,
        ResourceVersionDto version,
        String boundAgent
    ) {
        AssistantReleaseResourceDto existing = snapshotMap.get(version.id());
        if (existing == null) {
            snapshotMap.put(
                version.id(),
                new AssistantReleaseResourceDto(
                    resource.id(),
                    resource.name(),
                    resource.type(),
                    version.id(),
                    version.version(),
                    List.of(boundAgent),
                    version.configuration()
                )
            );
        } else {
            snapshotMap.put(
                version.id(),
                new AssistantReleaseResourceDto(
                    existing.resourceId(),
                    existing.resourceName(),
                    existing.resourceType(),
                    existing.resourceVersionId(),
                    existing.resourceVersion(),
                    append(existing.boundAgents(), boundAgent),
                    existing.configuration()
                )
            );
        }
    }

    private ObjectReferenceAnalysisDto analyzeDomainReferences(BusinessDomainDto domain) {
        return ObjectReferenceAnalyzer.analyzeDomain(domain, scenarios, resources, listKnowledgeBases());
    }

    private ObjectReferenceAnalysisDto analyzeScenarioReferences(ScenarioDto scenario) {
        return ObjectReferenceAnalyzer.analyzeScenario(scenario, assistants);
    }

    private ObjectReferenceAnalysisDto analyzeAssistantReferences(AssistantDto assistant) {
        return ObjectReferenceAnalyzer.analyzeAssistant(
            toAssistantView(assistant),
            agents,
            playbooks,
            listResources(),
            listKnowledgeBases(),
            assistantReleases.getOrDefault(assistant.id(), List.of())
        );
    }

    private ObjectReferenceAnalysisDto analyzePlaybookReferences(PlaybookDto playbook) {
        AssistantDto assistant = toAssistantView(findAssistant(playbook.assistantId()));
        return ObjectReferenceAnalyzer.analyzePlaybook(
            playbook,
            assistant.name(),
            agents,
            assistantReleases.getOrDefault(playbook.assistantId(), List.of())
        );
    }

    private ObjectReferenceAnalysisDto analyzeAgentReferences(AgentDto agent) {
        AssistantDto assistant = toAssistantView(findAssistant(agent.assistantId()));
        return ObjectReferenceAnalyzer.analyzeAgent(
            agent,
            assistant.name(),
            listResources(),
            listKnowledgeBases(),
            assistantReleases.getOrDefault(agent.assistantId(), List.of())
        );
    }

    private ObjectReferenceAnalysisDto analyzeResourceReferences(ResourceDto resource) {
        return ObjectReferenceAnalyzer.analyzeResource(
            resource,
            repository.findResourceBindings(resource.id()),
            repository.findReleaseResourceRefs(resource.id()),
            this::resolveSourceName,
            this::resolveAssistantReleaseName
        );
    }

    private ObjectReferenceAnalysisDto analyzeKnowledgeBaseReferences(KnowledgeBaseDto knowledgeBase) {
        return ObjectReferenceAnalyzer.analyzeKnowledgeBase(
            knowledgeBase,
            repository.findKnowledgeBindings(knowledgeBase.id()),
            repository.findReleaseKnowledgeRefs(knowledgeBase.id()),
            this::resolveSourceName,
            this::resolveAssistantReleaseName,
            this::findAssistantReleaseById
        );
    }

    private List<ObjectReferenceRelationDto> relationsByImpactLevel(ObjectReferenceAnalysisDto analysis, String impactLevel) {
        return analysis.relations().stream()
            .filter(relation -> impactLevel.equals(relation.impactLevel()))
            .toList();
    }

    private List<DeletionCascadeItemDto> buildCascadeDeletes(String objectType, String objectId) {
        return switch (objectType) {
            case "DOMAIN", "SCENARIO" -> List.of();
            case "ASSISTANT" -> buildAssistantCascadeDeletes(objectId);
            case "PLAYBOOK", "AGENT" -> List.of();
            case "RESOURCE" -> buildResourceCascadeDeletes(objectId);
            case "KNOWLEDGE_BASE" -> buildKnowledgeBaseCascadeDeletes(objectId);
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
    }

    private List<DeletionCascadeItemDto> buildAssistantCascadeDeletes(String assistantId) {
        List<DeletionCascadeItemDto> cascadeDeletes = new ArrayList<>();
        AssistantDto assistant = toAssistantView(findAssistant(assistantId));
        assistantReleases.getOrDefault(assistantId, List.of()).stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .forEach(release -> cascadeDeletes.add(new DeletionCascadeItemDto(
                "DELETE",
                "ASSISTANT_RELEASE",
                "ASSISTANT_RELEASE",
                release.id(),
                assistant.name() + "@" + release.releaseVersion(),
                "删除助手后会同步删除该助手的发布快照。",
                release.releaseVersion(),
                null,
                null
            )));
        return cascadeDeletes;
    }

    private List<DeletionCascadeItemDto> buildResourceCascadeDeletes(String resourceId) {
        ResourceDto resource = toResourceView(findResource(resourceId));
        return versionsFor(resourceId).stream()
            .map(version -> new DeletionCascadeItemDto(
                "DELETE",
                "RESOURCE_VERSION",
                "RESOURCE_VERSION",
                version.id(),
                resource.name() + "@" + version.version(),
                "删除资源后会同步删除该资源的全部版本记录。",
                null,
                version.version(),
                null
            ))
            .toList();
    }

    private List<DeletionCascadeItemDto> buildKnowledgeBaseCascadeDeletes(String knowledgeBaseId) {
        KnowledgeBaseDto knowledgeBase = getKnowledgeBase(knowledgeBaseId);
        return knowledgeBase.releases().stream()
            .filter(release -> release.status() == VersionStatus.DRAFT)
            .map(release -> new DeletionCascadeItemDto(
                "DELETE",
                "KNOWLEDGE_BASE_DRAFT_RELEASE",
                "KNOWLEDGE_RELEASE",
                release.id(),
                knowledgeBase.name() + "@" + release.version(),
                "删除知识库后会同步删除未发布的知识发布版本。",
                null,
                null,
                release.version()
            ))
            .toList();
    }

    private String findObjectDeletionBlocker(String objectType, String objectId) {
        ObjectReferenceAnalysisDto analysis = objectReferences(objectType, objectId);
        return relationsByImpactLevel(analysis, "BLOCKS_DELETION").stream()
            .map(relation -> toDeletionMessage(analysis.objectType(), relation))
            .findFirst()
            .orElse(null);
    }

    private String findResourceVersionDeletionBlocker(String resourceId, String versionId) {
        return objectReferences("RESOURCE", resourceId).relations().stream()
            .filter(relation -> "BLOCKS_DELETION".equals(relation.impactLevel()))
            .filter(relation -> versionId.equals(relation.resourceVersionId()))
            .map(this::toResourceVersionDeletionMessage)
            .findFirst()
            .orElse(null);
    }

    private List<ResourceReferenceDto> toResourceReferences(ResourceDto resource, ObjectReferenceAnalysisDto analysis) {
        return analysis.relations().stream()
            .map(relation -> new ResourceReferenceDto(
                resource.id(),
                resource.name(),
                resource.type(),
                resource.shareScope(),
                resource.ownerType() + ":" + resource.ownerId(),
                resource.latestVersion() == null ? null : resource.latestVersion().version(),
                resource.effectiveVersion() == null ? null : resource.effectiveVersion().version(),
                relation.relationKind(),
                relation.targetType(),
                relation.targetId(),
                relation.targetName(),
                relation.resourceVersionId(),
                relation.resourceVersion(),
                "BLOCKS_DELETION".equals(relation.impactLevel())
            ))
            .toList();
    }

    private List<KnowledgeReferenceDto> toKnowledgeReferences(ObjectReferenceAnalysisDto analysis) {
        return analysis.relations().stream()
            .map(relation -> new KnowledgeReferenceDto(
                analysis.objectId(),
                relation.relationKind(),
                relation.targetType(),
                relation.targetId(),
                relation.targetName(),
                relation.knowledgeReleaseId(),
                relation.knowledgeReleaseVersion(),
                "BLOCKS_DELETION".equals(relation.impactLevel())
            ))
            .toList();
    }

    private String resolveSourceName(String sourceKey) {
        int separatorIndex = sourceKey.indexOf(':');
        if (separatorIndex < 0) {
            return sourceKey;
        }
        return resolveSourceName(sourceKey.substring(0, separatorIndex), sourceKey.substring(separatorIndex + 1));
    }

    private String resolveSourceName(String sourceType, String sourceId) {
        return switch (sourceType) {
            case "ASSISTANT" -> assistants.stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AssistantDto::name).orElse(sourceId);
            case "AGENT" -> agents.stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AgentDto::name).orElse(sourceId);
            default -> sourceId;
        };
    }

    private AssistantReleaseDto findAssistantReleaseById(String releaseId) {
        return assistantReleases.values().stream()
            .flatMap(List::stream)
            .filter(release -> release.id().equals(releaseId))
            .findFirst()
            .orElse(null);
    }

    private String resolveAssistantReleaseName(CatalogRepository.ReleaseResourceRef ref) {
        AssistantDto assistant = toAssistantView(findAssistant(ref.assistantId()));
        return assistant.name() + "@" + findReleaseVersion(ref.assistantId(), ref.releaseId());
    }

    private String resolveAssistantReleaseName(String releaseId) {
        AssistantReleaseDto release = findAssistantReleaseById(releaseId);
        if (release == null) {
            return releaseId;
        }
        AssistantDto assistant = toAssistantView(findAssistant(release.assistantId()));
        return assistant.name() + "@" + release.releaseVersion();
    }

    private String findReleaseVersion(String assistantId, String releaseId) {
        List<AssistantReleaseDto> releases = assistantReleases.get(assistantId);
        if (releases == null) return releaseId;
        return releases.stream()
            .filter(r -> r.id().equals(releaseId)).findFirst()
            .map(AssistantReleaseDto::releaseVersion).orElse(releaseId);
    }

    private String toDeletionMessage(String objectType, ObjectReferenceRelationDto relation) {
        return switch (objectType) {
            case "DOMAIN" -> switch (relation.relationKind()) {
                case "DOMAIN_SCENARIO" -> "business domain still contains scenario: " + relation.targetName();
                case "DOMAIN_RESOURCE" -> "business domain still contains resource: " + relation.targetName();
                case "DOMAIN_KNOWLEDGE_BASE" -> "business domain still contains knowledge base: " + relation.targetName();
                default -> "business domain is still referenced: " + relation.targetName();
            };
            case "SCENARIO" -> "scenario still contains assistant: " + relation.targetName();
            case "ASSISTANT" -> switch (relation.relationKind()) {
                case "ASSISTANT_AGENT" -> "assistant still contains agent: " + relation.targetName();
                case "ASSISTANT_PLAYBOOK" -> "assistant still contains playbook: " + relation.targetName();
                case "ASSISTANT_PRIVATE_RESOURCE" -> "assistant still owns resource: " + relation.targetName();
                case "ASSISTANT_PRIVATE_KNOWLEDGE_BASE" -> "assistant still owns knowledge base: " + relation.targetName();
                default -> "assistant is still referenced: " + relation.targetName();
            };
            case "PLAYBOOK" -> switch (relation.relationKind()) {
                case "PLAYBOOK_AGENT_ENABLED" -> "playbook is still enabled on agent: " + relation.targetName();
                default -> "playbook is still referenced: " + relation.targetName();
            };
            case "RESOURCE" -> switch (relation.relationKind()) {
                case "ASSISTANT_DEFAULT_MODEL" -> "resource is used as assistant default model: " + relation.targetName();
                case "AGENT_OVERRIDE_MODEL" -> "resource is used as agent override model: " + relation.targetName();
                case "AGENT_SKILL_ENABLED" -> "resource is used as agent skill: " + relation.targetName();
                case "AGENT_TOOL_ENABLED" -> "resource is used as agent tool: " + relation.targetName();
                default -> "resource is still referenced: " + relation.targetName();
            };
            case "KNOWLEDGE_BASE" -> switch (relation.relationKind()) {
                case "KNOWLEDGE_BASE_EFFECTIVE_RELEASE" -> "knowledge base has a published release: " + relation.targetName();
                default -> "knowledge base is still referenced: " + relation.targetName();
            };
            default -> null;
        };
    }

    private String toResourceVersionDeletionMessage(ObjectReferenceRelationDto relation) {
        return "resource version is still referenced: " + relation.targetName();
    }

    private String normalizeReferenceObjectType(String objectType) {
        String normalized = objectType == null ? "" : objectType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DOMAIN", "SCENARIO", "ASSISTANT", "PLAYBOOK", "AGENT", "RESOURCE", "KNOWLEDGE_BASE" -> normalized;
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
    }

    private void restore(CatalogRepository.CatalogSnapshot snapshot) {
        domains.clear();
        domains.addAll(snapshot.domains());
        scenarios.clear();
        scenarios.addAll(snapshot.scenarios());
        assistants.clear();
        assistants.addAll(snapshot.assistants().stream()
            .map(assistant -> new AssistantDto(
                assistant.id(),
                assistant.scenarioId(),
                assistant.name(),
                assistant.description(),
                assistant.version(),
                assistant.agents(),
                assistant.playbooks() == null ? List.of() : assistant.playbooks().stream().map(this::normalizePlaybook).toList(),
                assistant.currentRelease(),
                assistant.releases(),
                normalizePrimaryAgentId(assistant.primaryAgentId()),
                normalizeAssistantOwnerPolicy(assistant.ownerPolicy()),
                normalizeAssistantSessionPolicy(assistant.sessionPolicy()),
                normalizeAssistantReplyPolicy(assistant.replyPolicy()),
                normalizeAssistantPlaybookPolicy(assistant.playbookPolicy()),
                normalizeAssistantModelPolicy(assistant.modelPolicy()),
                normalizeOptionalText(assistant.privacyModelResourceId()),
                assistant.privacyMappingEnabled(),
                normalizeKnowledgeAccessPolicy(assistant.knowledgeAccessPolicy()),
                normalizeMemoryPolicy(assistant.memoryPolicy())
            ))
            .toList());
        resources.clear();
        resources.addAll(snapshot.resources());
        agents.clear();
        agents.addAll(snapshot.agents().stream().map(this::normalizeLoadedAgent).toList());
        playbooks.clear();
        playbooks.addAll(snapshot.playbooks().stream().map(this::normalizePlaybook).toList());
        resourceVersions.clear();
        snapshot.resourceVersions().forEach((resourceId, versions) -> {
            ResourceType resourceType = resources.stream()
                .filter(resource -> resource.id().equals(resourceId))
                .map(ResourceDto::type)
                .findFirst()
                .orElse(null);
            resourceVersions.put(
                resourceId,
                versions.stream()
                    .map(version -> new StoredResourceVersion(
                        version.id(),
                        version.resourceId(),
                        version.version(),
                        version.status(),
                        version.summary(),
                        version.configDigest(),
                        version.createdAt(),
                        version.publishedAt(),
                        normalizeConfiguration(
                            resourceType == null ? version.configuration().type() : resourceType,
                            version.configuration()
                        )
                    ))
                    .toList()
            );
        });
        assistantReleases.clear();
        snapshot.assistantReleases().forEach((assistantId, releases) -> assistantReleases.put(
            assistantId,
            releases.stream()
                .map(release -> new AssistantReleaseDto(
                    release.id(),
                    release.assistantId(),
                    release.releaseVersion(),
                    release.status(),
                    release.createdAt(),
                    release.publishedAt(),
                    normalizeKnowledgeBindingSnapshot(release.assistantKnowledgeBinding()),
                    normalizeDefaultModelBinding(release.defaultModelBinding()),
                    normalizeDefaultModelBinding(release.privacyModelBinding()),
                    release.privacyMappingEnabled(),
                    release.resources().stream()
                        .map(resource -> new AssistantReleaseResourceDto(
                            resource.resourceId(),
                            resource.resourceName(),
                            resource.resourceType(),
                            resource.resourceVersionId(),
                            resource.resourceVersion(),
                            resource.boundAgents(),
                            normalizeConfiguration(resource.resourceType(), resource.configuration())
                        ))
                        .toList(),
                    release.agents().stream()
                        .map(agent -> new AssistantReleaseAgentDto(
                            agent.agentId(),
                            agent.name(),
                            agent.role(),
                            agent.responsibility(),
                            normalizeAgentExecutionPolicy(agent.executionPolicy()),
                            normalizeKnowledgeBindingSnapshot(agent.knowledgeBinding()),
                            normalizeDefaultModelBinding(agent.effectivePrivacyModelBinding()),
                            agent.effectivePrivacyMappingEnabled(),
                            agent.canOwnSession(),
                            normalizeAllowedActions(agent.allowedActions()),
                            agent.switchableOwnerAgentIds(),
                            agent.playbookIds(),
                            agent.skillResourceVersionIds(),
                            agent.toolResourceVersionIds()
                        ))
                        .toList(),
                    release.playbooks() == null ? List.of() : release.playbooks().stream().map(this::normalizePlaybook).toList(),
                    normalizePrimaryAgentId(release.primaryAgentId()),
                    normalizeAssistantOwnerPolicy(release.ownerPolicy()),
                    normalizeAssistantSessionPolicy(release.sessionPolicy()),
                    normalizeAssistantReplyPolicy(release.replyPolicy()),
                    normalizeAssistantPlaybookPolicy(release.playbookPolicy()),
                    normalizeAssistantModelPolicy(release.modelPolicy()),
                    normalizeKnowledgeAccessPolicy(release.knowledgeAccessPolicy()),
                    normalizeMemoryPolicy(release.memoryPolicy())
                ))
                .toList()
        ));
    }

    private AgentDto normalizeLoadedAgent(AgentDto agent) {
        AgentExecutionPolicyDto normalizedPolicy = normalizeAgentExecutionPolicy(agent.executionPolicy());
        List<String> enabledSkillResourceIds = normalizedPolicy.skillResourceIds().stream()
            .filter(resourceId -> resources.stream().anyMatch(item -> item.id().equals(resourceId) && item.type() == ResourceType.SKILL))
            .toList();
        List<String> enabledToolResourceIds = normalizedPolicy.toolResourceIds().stream()
            .filter(resourceId -> resources.stream().anyMatch(item -> item.id().equals(resourceId) && item.type() == ResourceType.TOOL))
            .toList();
        return new AgentDto(
            agent.id(),
            agent.assistantId(),
            agent.name(),
            agent.role(),
            agent.responsibility(),
            new AgentExecutionPolicyDto(
                normalizedPolicy.inheritAssistantDefaults(),
                normalizedPolicy.modelResourceId(),
                normalizedPolicy.privacyModelResourceId(),
                normalizedPolicy.privacyMappingEnabled(),
                normalizedPolicy.systemPrompt(),
                normalizedPolicy.knowledgeEnabled(),
                normalizedPolicy.inheritAssistantKnowledge(),
                normalizedPolicy.knowledgeBaseId(),
                normalizedPolicy.memoryWindowSize(),
                List.copyOf(enabledSkillResourceIds),
                List.copyOf(enabledToolResourceIds)
            ),
            agent.canOwnSession(),
            normalizeAllowedActions(agent.allowedActions()),
            agent.switchableOwnerAgentIds(),
            agent.playbookIds()
        );
    }

    private List<PlaybookDto> playbooksForAssistant(String assistantId) {
        return playbooks.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparing(PlaybookDto::name))
            .map(this::normalizePlaybook)
            .toList();
    }

    private PlaybookDto normalizePlaybook(PlaybookDto playbook) {
        return new PlaybookDto(
            playbook.id(),
            playbook.assistantId(),
            playbook.name(),
            playbook.description(),
            normalizeOptionalText(playbook.inputSchema()),
            normalizeOptionalText(playbook.resultSchema()),
            normalizePlaybookExecutionPolicy(playbook.executionPolicy()),
            playbook.allowHumanTask(),
            playbook.allowExternalInteraction(),
            playbook.entryNodeKey(),
            playbook.nodes() == null ? List.of() : playbook.nodes().stream().map(this::normalizePlaybookNode).toList(),
            playbook.edges() == null ? List.of() : playbook.edges().stream().map(this::normalizePlaybookEdge).toList()
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
            node.config() == null ? Map.of() : Map.copyOf(node.config())
        );
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

    private void validatePlaybookDefinition(PlaybookDto playbook) {
        if (playbook.nodes().isEmpty()) {
            throw new IllegalStateException("playbook must contain at least one node");
        }
        Set<String> nodeKeys = new HashSet<>();
        for (PlaybookNodeDto node : playbook.nodes()) {
            if (!nodeKeys.add(node.nodeKey())) {
                throw new IllegalStateException("duplicate playbook nodeKey: " + node.nodeKey());
            }
            if (node.nodeType() == null) {
                throw new IllegalStateException("playbook nodeType is required");
            }
            if (node.nodeType() == com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.STEP) {
                validateStepNode(node);
            }
            if (!playbook.allowHumanTask() && node.nodeType() == com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.HUMAN_TASK) {
                throw new IllegalStateException("playbook does not allow HUMAN_TASK nodes");
            }
            if (!playbook.allowExternalInteraction()
                && node.nodeType() == com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.EXTERNAL_INTERACTION) {
                throw new IllegalStateException("playbook does not allow EXTERNAL_INTERACTION nodes");
            }
        }
        if (!nodeKeys.contains(playbook.entryNodeKey())) {
            throw new IllegalStateException("playbook entryNodeKey must reference an existing node");
        }
        for (PlaybookEdgeDto edge : playbook.edges()) {
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

    private void persistState() {
        repository.save(new CatalogRepository.CatalogSnapshot(
            List.copyOf(domains),
            List.copyOf(scenarios),
            List.copyOf(assistants),
            List.copyOf(agents),
            List.copyOf(playbooks),
            List.copyOf(resources),
            Map.copyOf(resourceVersions),
            Map.copyOf(assistantReleases)
        ));
    }

    private BusinessDomainDto findDomain(String domainId) {
        return domains.stream()
            .filter(item -> item.id().equals(domainId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("business domain not found: " + domainId));
    }

    private ScenarioDto findScenario(String scenarioId) {
        return scenarios.stream()
            .filter(item -> item.id().equals(scenarioId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("scenario not found: " + scenarioId));
    }

    private ResourceDto findResource(String resourceId) {
        return resources.stream()
            .filter(item -> item.id().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("resource not found: " + resourceId));
    }

    private AssistantDto findAssistant(String assistantId) {
        return assistants.stream()
            .filter(item -> item.id().equals(assistantId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("assistant not found: " + assistantId));
    }

    private AgentDto findAgent(String agentId) {
        return agents.stream()
            .filter(item -> item.id().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("agent not found: " + agentId));
    }

    private PlaybookDto findPlaybook(String playbookId) {
        return playbooks.stream()
            .filter(item -> item.id().equals(playbookId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("playbook not found: " + playbookId));
    }

    private void validateAgentPlaybookReferences(String assistantId, List<String> playbookIds) {
        if (playbookIds == null || playbookIds.isEmpty()) {
            return;
        }
        Set<String> allowedPlaybooks = playbooks.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .map(PlaybookDto::id)
            .collect(java.util.stream.Collectors.toSet());
        for (String playbookId : playbookIds) {
            if (!allowedPlaybooks.contains(playbookId)) {
                throw new IllegalStateException("agent references unknown playbook: " + playbookId);
            }
        }
    }

    private ResourceVersionDto findResourceVersion(String resourceId, String versionId) {
        return versionsFor(resourceId).stream()
            .filter(item -> item.id().equals(versionId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("resource version not found: " + resourceId + "/" + versionId));
    }

    private List<ResourceVersionDto> versionsFor(String resourceId) {
        return resourceVersions.getOrDefault(resourceId, List.of()).stream()
            .sorted(Comparator.comparing(StoredResourceVersion::createdAt))
            .map(this::toResourceVersionDto)
            .toList();
    }

    private List<StoredResourceVersion> storedVersionsFor(String resourceId) {
        return resourceVersions.getOrDefault(resourceId, List.of()).stream()
            .sorted(Comparator.comparing(StoredResourceVersion::createdAt))
            .toList();
    }

    private ResourceVersionDto effectiveVersion(ResourceDto resource) {
        return versionsFor(resource.id()).stream()
            .filter(item -> item.status() == VersionStatus.PUBLISHED)
            .reduce((__, item) -> item)
            .orElseGet(() -> versionsFor(resource.id()).stream().findFirst().orElseThrow());
    }

    private String nextAssistantReleaseVersion(String assistantId) {
        List<AssistantReleaseDto> releases = assistantReleases.getOrDefault(assistantId, List.of()).stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt))
            .toList();
        if (releases.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = releases.getLast().releaseVersion().split("\\.");
        int patch = Integer.parseInt(segments[2]) + 1;
        return segments[0] + "." + segments[1] + "." + patch;
    }

    private ResourceVersionConfigurationDto normalizeConfiguration(ResourceType type, ResourceVersionConfigurationDto configuration) {
        if (configuration == null) {
            return defaultConfiguration(type);
        }
        return switch (type) {
            case TOOL -> new ResourceVersionConfigurationDto(type, normalizeToolConfig(configuration.tool()), null, null);
            case LLM_MODEL -> new ResourceVersionConfigurationDto(type, null, configuration.llmModel() == null ? defaultConfiguration(type).llmModel() : configuration.llmModel(), null);
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
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID", "custom-compatible-model"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_BASE_URL", "http://localhost:11434/v1"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR", "OPENAI_COMPATIBLE_API_KEY"),
                    0.2,
                    1200,
                    false
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
            List.of(new ToolOperationDto("invoke", "执行通用工具动作", "{\"input\":\"string\"}", "{\"type\":\"object\",\"required\":[\"output\"],\"properties\":{\"output\":{\"type\":\"string\"}},\"additionalProperties\":false}")),
            ToolProviderType.HTTP,
            "SERVICE_ACCOUNT",
            15,
            "NONE",
            new HttpToolProviderConfigDto("http://localhost:8081/tools/invoke", "POST"),
            null
        );
    }

    private ToolConfigDto normalizeToolConfig(ToolConfigDto configuration) {
        ToolConfigDto defaults = defaultToolConfig();
        ToolProviderType providerType = configuration == null || configuration.providerType() == null ? defaults.providerType() : configuration.providerType();
        List<ToolOperationDto> operations = normalizeToolOperations(configuration == null ? null : configuration.operations());
        if (operations.isEmpty()) {
            operations = defaults.operations();
        }
        HttpToolProviderConfigDto http = providerType == ToolProviderType.HTTP
            ? normalizeHttpToolProvider(configuration == null ? null : configuration.http())
            : null;
        McpToolProviderConfigDto mcp = providerType == ToolProviderType.MCP
            ? normalizeMcpToolProvider(configuration == null ? null : configuration.mcp(), operations)
            : null;

        return new ToolConfigDto(
            List.copyOf(operations),
            providerType,
            configuration == null || configuration.authType() == null || configuration.authType().isBlank() ? defaults.authType() : configuration.authType(),
            configuration == null || configuration.timeoutSeconds() <= 0 ? defaults.timeoutSeconds() : configuration.timeoutSeconds(),
            configuration == null || configuration.retryPolicy() == null || configuration.retryPolicy().isBlank() ? defaults.retryPolicy() : configuration.retryPolicy(),
            http,
            mcp
        );
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

    private HttpToolProviderConfigDto normalizeHttpToolProvider(HttpToolProviderConfigDto configuration) {
        HttpToolProviderConfigDto defaults = defaultToolConfig().http();
        return new HttpToolProviderConfigDto(
            configuration == null || configuration.endpoint() == null || configuration.endpoint().isBlank() ? defaults.endpoint() : configuration.endpoint(),
            configuration == null || configuration.method() == null || configuration.method().isBlank() ? defaults.method() : configuration.method()
        );
    }

    private McpToolProviderConfigDto normalizeMcpToolProvider(McpToolProviderConfigDto configuration, List<ToolOperationDto> operations) {
        Map<String, String> operationMappings = new LinkedHashMap<>();
        Map<String, String> requestedMappings = configuration == null || configuration.operationMappings() == null
            ? Map.of()
            : configuration.operationMappings();
        for (ToolOperationDto operation : operations) {
            operationMappings.put(operation.name(), normalizeOptionalText(requestedMappings.getOrDefault(operation.name(), operation.name())));
        }
        return new McpToolProviderConfigDto(
            configuration == null || configuration.serverName() == null || configuration.serverName().isBlank() ? "default-mcp-server" : configuration.serverName(),
            configuration == null || configuration.transport() == null || configuration.transport().isBlank() ? "STREAMABLE_HTTP" : configuration.transport(),
            configuration == null || configuration.connectionUri() == null || configuration.connectionUri().isBlank() ? "http://localhost:8081/mcp" : configuration.connectionUri(),
            configuration == null || configuration.namespace() == null || configuration.namespace().isBlank() ? "default.namespace" : configuration.namespace(),
            configuration == null || configuration.heartbeatSeconds() <= 0 ? 30 : configuration.heartbeatSeconds(),
            Map.copyOf(operationMappings)
        );
    }

    private AssistantModelPolicyDto normalizeAssistantModelPolicy(AssistantModelPolicyDto policy) {
        if (policy == null) {
            return new AssistantModelPolicyDto(null);
        }
        String defaultModelResourceId = policy.defaultModelResourceId();
        if (defaultModelResourceId == null || defaultModelResourceId.isBlank()) {
            return new AssistantModelPolicyDto(null);
        }
        return new AssistantModelPolicyDto(defaultModelResourceId.trim());
    }

    private String normalizePrimaryAgentId(String primaryAgentId) {
        return normalizeOptionalText(primaryAgentId);
    }

    private AssistantOwnerPolicyDto normalizeAssistantOwnerPolicy(AssistantOwnerPolicyDto policy) {
        if (policy == null) {
            return new AssistantOwnerPolicyDto(3);
        }
        return new AssistantOwnerPolicyDto(policy.maxOwnerSwitchesPerTurn() <= 0 ? 3 : policy.maxOwnerSwitchesPerTurn());
    }

    private AssistantSessionPolicyDto normalizeAssistantSessionPolicy(AssistantSessionPolicyDto policy) {
        if (policy == null) {
            return new AssistantSessionPolicyDto("PT30M", "P7D", 20_000);
        }
        String idleTimeout = normalizeOptionalText(policy.idleTimeout());
        String maxWorkflowAge = normalizeOptionalText(policy.maxWorkflowAge());
        return new AssistantSessionPolicyDto(
            idleTimeout == null ? "PT30M" : idleTimeout,
            maxWorkflowAge == null ? "P7D" : maxWorkflowAge,
            policy.maxWorkflowHistoryEvents() <= 0 ? 20_000 : policy.maxWorkflowHistoryEvents()
        );
    }

    private AssistantReplyPolicyDto normalizeAssistantReplyPolicy(AssistantReplyPolicyDto policy) {
        if (policy == null) {
            return new AssistantReplyPolicyDto(true);
        }
        return new AssistantReplyPolicyDto(policy.ownerOnly());
    }

    private AssistantPlaybookPolicyDto normalizeAssistantPlaybookPolicy(AssistantPlaybookPolicyDto policy) {
        if (policy == null) {
            return new AssistantPlaybookPolicyDto(null, null);
        }
        return new AssistantPlaybookPolicyDto(
            normalizeOptionalText(policy.timeoutPolicy()),
            normalizeOptionalText(policy.retryPolicy())
        );
    }

    private KnowledgeAccessPolicyDto normalizeKnowledgeAccessPolicy(KnowledgeAccessPolicyDto policy) {
        if (policy == null) {
            String defaultKnowledgeBaseId = resolveDefaultKnowledgeBaseId(null);
            return new KnowledgeAccessPolicyDto(defaultKnowledgeBaseId != null, defaultKnowledgeBaseId);
        }
        return new KnowledgeAccessPolicyDto(policy.enabled(), normalizeOptionalText(policy.knowledgeBaseId()));
    }

    private MemoryPolicyDto normalizeMemoryPolicy(MemoryPolicyDto policy) {
        if (policy == null) {
            return new MemoryPolicyDto(true, 8);
        }
        return new MemoryPolicyDto(policy.enabled(), policy.windowSize());
    }

    private AgentExecutionPolicyDto normalizeAgentExecutionPolicy(AgentExecutionPolicyDto policy) {
        if (policy == null) {
            return new AgentExecutionPolicyDto(true, null, null, null, "", false, true, null, 8, List.of(), List.of());
        }
        return new AgentExecutionPolicyDto(
            policy.inheritAssistantDefaults(),
            normalizeOptionalText(policy.modelResourceId()),
            normalizeOptionalText(policy.privacyModelResourceId()),
            policy.privacyMappingEnabled(),
            normalizeOptionalText(policy.systemPrompt()),
            policy.knowledgeEnabled(),
            policy.inheritAssistantKnowledge(),
            normalizeOptionalText(policy.knowledgeBaseId()),
            policy.memoryWindowSize(),
            policy.skillResourceIds() == null ? List.of() : List.copyOf(policy.skillResourceIds()),
            policy.toolResourceIds() == null ? List.of() : List.copyOf(policy.toolResourceIds())
        );
    }

    private List<AgentDecisionAction> normalizeAllowedActions(List<AgentDecisionAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of(
                AgentDecisionAction.REPLY,
                AgentDecisionAction.NO_REPLY,
                AgentDecisionAction.SWITCH_OWNER,
                AgentDecisionAction.RUN_PLAYBOOK,
                AgentDecisionAction.SESSION_HUMAN_HANDOFF
            );
        }
        return List.copyOf(actions);
    }

    private void validateResourceOwner(String domainId, String ownerType, String ownerId) {
        String normalizedOwnerType = requireText(ownerType, "resource.ownerType");
        String normalizedOwnerId = requireText(ownerId, "resource.ownerId");
        if ("DOMAIN".equals(normalizedOwnerType)) {
            if (!domainId.equals(normalizedOwnerId)) {
                throw new IllegalArgumentException("resource ownerId must match domainId when ownerType=DOMAIN");
            }
            return;
        }
        if (!"ASSISTANT".equals(normalizedOwnerType)) {
            throw new IllegalArgumentException("resource ownerType must be DOMAIN or ASSISTANT");
        }
        AssistantDto assistant = findAssistant(normalizedOwnerId);
        ScenarioDto scenario = findScenario(assistant.scenarioId());
        if (!domainId.equals(scenario.domainId())) {
            throw new IllegalArgumentException("assistant owner must belong to the same business domain as resource.domainId");
        }
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
        return new ResourceVersionDto(
            version.id(),
            version.resourceId(),
            version.version(),
            version.status(),
            version.summary(),
            version.createdAt(),
            version.publishedAt(),
            version.configuration()
        );
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> append(List<String> items, String item) {
        List<String> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveDefaultKnowledgeBaseId(String preferredKnowledgeBaseId) {
        return knowledgeService.resolveDefaultKnowledgeBaseId(preferredKnowledgeBaseId);
    }

    private void validateAssistantModelPolicy(AssistantModelPolicyDto policy) {
        if (policy == null || policy.defaultModelResourceId() == null || policy.defaultModelResourceId().isBlank()) {
            return;
        }
        ResourceDto resource = findResource(policy.defaultModelResourceId());
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException("assistant default model must reference an LLM_MODEL resource");
        }
    }

    private void validatePrivacyModelResourceId(String resourceId, String fieldName) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = findResource(resourceId);
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException(fieldName + " must reference an LLM_MODEL resource");
        }
    }

    private void validateAssistantOwnerConfiguration(AssistantDto assistant) {
        String primaryAgentId = assistant.primaryAgentId();
        if (primaryAgentId == null || primaryAgentId.isBlank()) {
            return;
        }
        AgentDto primaryAgent = agents.stream()
            .filter(item -> item.assistantId().equals(assistant.id()))
            .filter(item -> item.id().equals(primaryAgentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("assistant primaryAgentId must reference an existing agent under the same assistant"));
        if (!primaryAgent.canOwnSession()) {
            throw new IllegalArgumentException("assistant primaryAgentId must reference an agent with canOwnSession=true");
        }
    }

    private void clearDeletedPrimaryAgent(String assistantId, String deletedAgentId) {
        AssistantDto assistant = findAssistant(assistantId);
        if (!deletedAgentId.equals(assistant.primaryAgentId())) {
            return;
        }
        AssistantDto updated = new AssistantDto(
            assistant.id(),
            assistant.scenarioId(),
            assistant.name(),
            assistant.description(),
            assistant.version(),
            assistant.agents(),
            assistant.playbooks(),
            assistant.currentRelease(),
            assistant.releases(),
            null,
            assistant.ownerPolicy(),
            assistant.sessionPolicy(),
            assistant.replyPolicy(),
            assistant.playbookPolicy(),
            assistant.modelPolicy(),
            assistant.privacyModelResourceId(),
            assistant.privacyMappingEnabled(),
            assistant.knowledgeAccessPolicy(),
            assistant.memoryPolicy()
        );
        replace(assistants, AssistantDto::id, updated);
    }

    private void ensureAssistantReadyForPublication(AssistantDto assistant) {
        AssistantModelPolicyDto policy = assistant.modelPolicy();
        if (policy == null || policy.defaultModelResourceId() == null || policy.defaultModelResourceId().isBlank()) {
            throw new IllegalStateException("assistant default model must be configured before publishing");
        }
        if (assistant.primaryAgentId() == null || assistant.primaryAgentId().isBlank()) {
            throw new IllegalStateException("assistant primaryAgentId must be configured before publishing");
        }
        validateAssistantOwnerConfiguration(assistant);
        if (assistant.privacyMappingEnabled()) {
            resolvePrivacyModelBinding(assistant);
        }
        for (AgentDto agent : orderAgentsForAssistant(assistant.id())) {
            if (!resolveEffectivePrivacyMappingEnabled(assistant, agent)) {
                continue;
            }
            resolvePrivacyModelBinding(assistant, agent);
        }
    }

    private DefaultModelBindingDto resolveDefaultModelBinding(AssistantDto assistant) {
        String resourceId = assistant.modelPolicy().defaultModelResourceId();
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("assistant default model must be configured before publishing");
        }
        return resolveLlmModelBinding(resourceId, false, "assistant default model");
    }

    private DefaultModelBindingDto resolvePrivacyModelBinding(AssistantDto assistant) {
        if (!assistant.privacyMappingEnabled()) {
            return null;
        }
        String resourceId = normalizeOptionalText(assistant.privacyModelResourceId());
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("assistant privacy model must be configured when privacy mapping is enabled");
        }
        return resolveLlmModelBinding(resourceId, true, "assistant privacy model");
    }

    private DefaultModelBindingDto resolvePrivacyModelBinding(AssistantDto assistant, AgentDto agent) {
        String resourceId = resolveEffectivePrivacyModelResourceId(assistant, agent);
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("effective privacy model must be configured when privacy mapping is enabled");
        }
        return resolveLlmModelBinding(resourceId, true, "effective privacy model");
    }

    private String resolveEffectivePrivacyModelResourceId(AssistantDto assistant, AgentDto agent) {
        String override = agent.executionPolicy() == null ? null : normalizeOptionalText(agent.executionPolicy().privacyModelResourceId());
        if (override != null && !override.isBlank()) {
            return override;
        }
        return normalizeOptionalText(assistant.privacyModelResourceId());
    }

    private boolean resolveEffectivePrivacyMappingEnabled(AssistantDto assistant, AgentDto agent) {
        if (agent.executionPolicy() != null && agent.executionPolicy().privacyMappingEnabled() != null) {
            return agent.executionPolicy().privacyMappingEnabled();
        }
        return assistant.privacyMappingEnabled();
    }

    private DefaultModelBindingDto resolveLlmModelBinding(String resourceId, boolean requirePrivateDeployment, String usageLabel) {
        ResourceDto resource = toResourceView(findResource(resourceId));
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException(usageLabel + " must reference an LLM_MODEL resource");
        }
        ResourceVersionDto version = effectiveVersion(resource);
        LlmModelConfigDto modelConfig = version.configuration() == null ? null : version.configuration().llmModel();
        if (requirePrivateDeployment && (modelConfig == null || !modelConfig.privateDeployment())) {
            throw new IllegalStateException(usageLabel + " must reference an LLM_MODEL resource with privateDeployment=true");
        }
        return new DefaultModelBindingDto(
            resource.id(),
            resource.name(),
            version.id(),
            version.version(),
            modelConfig == null ? null : modelConfig.providerType(),
            modelConfig == null ? null : modelConfig.modelId()
        );
    }

    private DefaultModelBindingDto normalizeDefaultModelBinding(DefaultModelBindingDto binding) {
        if (binding == null) {
            return null;
        }
        return new DefaultModelBindingDto(
            normalizeOptionalText(binding.resourceId()),
            normalizeOptionalText(binding.resourceName()),
            normalizeOptionalText(binding.resourceVersionId()),
            normalizeOptionalText(binding.resourceVersion()),
            normalizeOptionalText(binding.providerType()),
            normalizeOptionalText(binding.modelId())
        );
    }

    private KnowledgeBindingSnapshotDto normalizeKnowledgeBindingSnapshot(KnowledgeBindingSnapshotDto binding) {
        if (binding == null) {
            return null;
        }
        return new KnowledgeBindingSnapshotDto(
            binding.knowledgeBaseId(),
            binding.knowledgeBaseName(),
            binding.knowledgeReleaseId(),
            binding.knowledgeReleaseVersion(),
            binding.snapshotId(),
            binding.defaultTopK(),
            binding.retrievalMode(),
            binding.minScore()
        );
    }

    private void ensureUniqueDomainName(String name, String excludedDomainId) {
        boolean exists = domains.stream().anyMatch(item ->
            !item.id().equals(excludedDomainId)
                && item.name().equalsIgnoreCase(name)
        );
        if (exists) {
            throw new IllegalArgumentException("business domain name already exists: " + name);
        }
    }

    private void ensureUniqueScenarioName(String domainId, String name, String excludedScenarioId) {
        boolean exists = scenarios.stream().anyMatch(item ->
            item.domainId().equals(domainId)
                && !item.id().equals(excludedScenarioId)
                && item.name().equalsIgnoreCase(name)
        );
        if (exists) {
            throw new IllegalArgumentException("scenario name already exists in domain: " + name);
        }
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

    private static <T, K> void replace(List<T> items, Function<T, K> keyExtractor, T replacement) {
        K replacementKey = keyExtractor.apply(replacement);
        items.removeIf(item -> keyExtractor.apply(item).equals(replacementKey));
        items.add(replacement);
    }

    private static final class NoOpKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        @Override
        public void startImport(String knowledgeBaseId, String importJobId) {
        }

        @Override
        public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
        }
    }
}
