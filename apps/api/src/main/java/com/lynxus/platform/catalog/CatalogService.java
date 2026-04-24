package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.lynxus.platform.event.PlatformEventService;
import com.lynxus.platform.knowledge.InMemoryKnowledgeRepository;
import com.lynxus.platform.knowledge.KnowledgeRepository;
import com.lynxus.platform.knowledge.KnowledgeService;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolConnectorType;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogRepository repository;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeService knowledgeService;
    private final PlatformEventService platformEventService;
    private final com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus;

    public CatalogService() {
        this(
            new InMemoryCatalogRepository(),
            new InMemoryKnowledgeRepository(),
            new KnowledgeServiceClient("http://127.0.0.1:8091", "in-memory-internal-token"),
            new NoOpKnowledgeWorkflowGateway(),
            PlatformEventService.disabled(),
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
        com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        this.repository = repository;
        this.knowledgeRepository = knowledgeService.repository();
        this.knowledgeService = knowledgeService;
        this.platformEventService = platformEventService;
        this.invalidationBus = invalidationBus;
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
        com.lynxus.platform.knowledge.KnowledgeRepository knowledgeRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService,
        com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        this.repository = repository;
        this.knowledgeRepository = knowledgeRepository;
        this.platformEventService = platformEventService;
        this.invalidationBus = invalidationBus;
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

    private List<BusinessDomainDto> domains(CatalogRepository repo) {
        return repo.listDomains();
    }

    private List<ScenarioDto> scenarios(CatalogRepository repo) {
        return repo.listScenarios();
    }

    private List<AssistantDto> assistants(CatalogRepository repo) {
        return repo.listAssistants();
    }

    private List<AgentDto> agents(CatalogRepository repo) {
        return repo.listAgents();
    }

    private List<PlaybookDto> playbooks(CatalogRepository repo) {
        return repo.listPlaybooks();
    }

    private List<ResourceDto> resources(CatalogRepository repo) {
        return repo.listResources();
    }

    private List<StoredResourceVersion> resourceVersions(CatalogRepository repo, String resourceId) {
        return repo.listResourceVersions(resourceId);
    }

    private List<AssistantReleaseDto> assistantReleases(CatalogRepository repo, String assistantId) {
        return repo.listAssistantReleases(assistantId);
    }

    private List<KnowledgeBaseDto> knowledgeBases(KnowledgeRepository repo) {
        return repo.listKnowledgeBases().stream()
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .map(item -> toKnowledgeBaseView(repo, item))
            .toList();
    }

    public CatalogSummaryDto summary() {
        return withReadState((state, knowledgeState) -> new CatalogSummaryDto(
            listDomains(state, knowledgeState),
            listScenarios(state),
            listAssistants(state),
            listAgents(state),
            listResources(state),
            knowledgeBases(knowledgeState),
            resourceCenter(state),
            resourceBlueprints()
        ));
    }

    public ObjectReferenceAnalysisDto objectReferences(String objectType, String objectId) {
        return withReadState((state, knowledgeState) -> {
        String normalizedObjectType = normalizeReferenceObjectType(objectType);
        return switch (normalizedObjectType) {
            case "DOMAIN" -> analyzeDomainReferences(state, knowledgeState, findDomain(state, objectId));
            case "SCENARIO" -> analyzeScenarioReferences(state, findScenario(state, objectId));
            case "ASSISTANT" -> analyzeAssistantReferences(state, knowledgeState, findAssistant(state, objectId));
            case "PLAYBOOK" -> analyzePlaybookReferences(state, findPlaybook(state, objectId));
            case "AGENT" -> analyzeAgentReferences(state, knowledgeState, findAgent(state, objectId));
            case "RESOURCE" -> analyzeResourceReferences(state, toResourceView(state, findResource(state, objectId)));
            case "KNOWLEDGE_BASE" -> analyzeKnowledgeBaseReferences(state, toKnowledgeBaseView(knowledgeState, findKnowledgeBase(knowledgeState, objectId)));
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
        });
    }

    public DeletionImpactPreviewDto deletionPreview(String objectType, String objectId) {
        return withReadState((state, knowledgeState) -> {
        String normalizedObjectType = normalizeReferenceObjectType(objectType);
        ObjectReferenceAnalysisDto analysis = objectReferences(state, knowledgeState, normalizedObjectType, objectId);
        List<ObjectReferenceRelationDto> blockers = relationsByImpactLevel(analysis, "BLOCKS_DELETION");
        List<ObjectReferenceRelationDto> advisories = relationsByImpactLevel(analysis, "ADVISORY");
        return new DeletionImpactPreviewDto(
            analysis.objectType(),
            analysis.objectId(),
            analysis.objectName(),
            blockers.isEmpty(),
            blockers,
            advisories,
            buildCascadeDeletes(state, knowledgeState, normalizedObjectType, objectId)
        );
        });
    }

    public List<BusinessDomainDto> listDomains() {
        return withReadState((state, knowledgeState) -> domains(state).stream()
            .sorted(Comparator.comparing(BusinessDomainDto::name))
            .map(item -> toDomainView(state, knowledgeState, item))
            .toList());
    }

    public BusinessDomainDto getDomain(String domainId) {
        return withReadState((state, knowledgeState) -> toDomainView(state, knowledgeState, findDomain(state, domainId)));
    }

    private List<BusinessDomainDto> listDomains(CatalogRepository state, KnowledgeRepository knowledgeState) {
        return domains(state).stream()
            .sorted(Comparator.comparing(BusinessDomainDto::name))
            .map(item -> toDomainView(state, knowledgeState, item))
            .toList();
    }

    private List<ScenarioDto> listScenarios(CatalogRepository state) {
        return scenarios(state).stream()
            .sorted(Comparator.comparing(ScenarioDto::name))
            .map(item -> toScenarioView(state, item))
            .toList();
    }

    private List<AssistantDto> listAssistants(CatalogRepository state) {
        return assistants(state).stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(item -> toAssistantView(state, item))
            .toList();
    }

    private List<AgentDto> listAgents(CatalogRepository state) {
        return agents(state).stream()
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();
    }

    private List<ResourceDto> listResources(CatalogRepository state) {
        return resources(state).stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(item -> toResourceView(state, item))
            .toList();
    }

    private ResourceCenterDto resourceCenter(CatalogRepository state) {
        List<ResourceReferenceDto> references = resources(state).stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(item -> toResourceView(state, item))
            .flatMap(resource -> toResourceReferences(resource, analyzeResourceReferences(state, resource)).stream())
            .toList();
        long domainShared = resources(state).stream().filter(item -> item.shareScope() == ShareScope.DOMAIN_SHARED).count();
        long privateCount = resources(state).stream().filter(item -> item.shareScope() == ShareScope.PRIVATE).count();
        return new ResourceCenterDto(resources(state).size(), Math.toIntExact(domainShared), Math.toIntExact(privateCount), references);
    }

    private ObjectReferenceAnalysisDto objectReferences(
        CatalogRepository state,
        KnowledgeRepository knowledgeState,
        String objectType,
        String objectId
    ) {
        return switch (objectType) {
            case "DOMAIN" -> analyzeDomainReferences(state, knowledgeState, findDomain(state, objectId));
            case "SCENARIO" -> analyzeScenarioReferences(state, findScenario(state, objectId));
            case "ASSISTANT" -> analyzeAssistantReferences(state, knowledgeState, findAssistant(state, objectId));
            case "PLAYBOOK" -> analyzePlaybookReferences(state, findPlaybook(state, objectId));
            case "AGENT" -> analyzeAgentReferences(state, knowledgeState, findAgent(state, objectId));
            case "RESOURCE" -> analyzeResourceReferences(state, toResourceView(state, findResource(state, objectId)));
            case "KNOWLEDGE_BASE" -> analyzeKnowledgeBaseReferences(state, toKnowledgeBaseView(knowledgeState, findKnowledgeBase(knowledgeState, objectId)));
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
    }

    public BusinessDomainDto createDomain(CreateDomainRequest request) {
        return withWriteState(state -> {
        String name = requireText(request.name(), "domain.name");
        ensureUniqueDomainName(state, name, null);
        BusinessDomainDto domain = new BusinessDomainDto(
            nextId("domain"),
            name,
            normalizeOptionalText(request.description()),
            List.of(),
            List.of(),
            List.of()
        );
        state.upsertDomain(domain);
        recordCatalogEvent("DOMAIN_CREATED", PlatformAggregateType.DOMAIN, domain.id(), Map.of("name", domain.name()));
        return toDomainView(state, knowledgeRepository, domain);
        });
    }

    public BusinessDomainDto updateDomain(String domainId, UpdateDomainRequest request) {
        return withWriteState(state -> {
        BusinessDomainDto existing = findDomain(state, domainId);
        String name = requireText(request.name(), "domain.name");
        ensureUniqueDomainName(state, name, existing.id());
        BusinessDomainDto updated = new BusinessDomainDto(
            existing.id(),
            name,
            normalizeOptionalText(request.description()),
            existing.scenarios(),
            existing.resources(),
            existing.knowledgeBases()
        );
        state.upsertDomain(updated);
        recordCatalogEvent("DOMAIN_UPDATED", PlatformAggregateType.DOMAIN, updated.id(), Map.of("name", updated.name()));
        return toDomainView(state, knowledgeRepository, updated);
        });
    }

    public BusinessDomainDto deleteDomain(String domainId) {
        return withWriteState(state -> {
        BusinessDomainDto existing = findDomain(state, domainId);
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "DOMAIN", domainId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        state.deleteDomain(domainId);
        recordCatalogEvent(
            "DOMAIN_DELETED",
            PlatformAggregateType.DOMAIN,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return toDomainView(state, knowledgeRepository, existing);
        });
    }

    public List<ScenarioDto> listScenarios() {
        return withReadState(this::listScenarios);
    }

    public ScenarioDto getScenario(String scenarioId) {
        return withReadState(state -> {
        return toScenarioView(state, findScenario(state, scenarioId));
        });
    }

    public ScenarioDto createScenario(CreateScenarioRequest request) {
        return withWriteState(state -> {
        String domainId = requireText(request.domainId(), "scenario.domainId");
        findDomain(state, domainId);
        String name = requireText(request.name(), "scenario.name");
        ensureUniqueScenarioName(state, domainId, name, null);
        ScenarioDto scenario = new ScenarioDto(
            nextId("scenario"),
            domainId,
            name,
            requireText(request.goal(), "scenario.goal"),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of()
        );
        state.upsertScenario(scenario);
        recordCatalogEvent("SCENARIO_CREATED", PlatformAggregateType.SCENARIO, scenario.id(), Map.of("name", scenario.name()));
        return toScenarioView(state, scenario);
        });
    }

    public ScenarioDto updateScenario(String scenarioId, UpdateScenarioRequest request) {
        return withWriteState(state -> {
        ScenarioDto existing = findScenario(state, scenarioId);
        String name = requireText(request.name(), "scenario.name");
        ensureUniqueScenarioName(state, existing.domainId(), name, existing.id());
        ScenarioDto updated = new ScenarioDto(
            existing.id(),
            existing.domainId(),
            name,
            requireText(request.goal(), "scenario.goal"),
            existing.version(),
            existing.assistants()
        );
        state.upsertScenario(updated);
        recordCatalogEvent("SCENARIO_UPDATED", PlatformAggregateType.SCENARIO, updated.id(), Map.of("name", updated.name()));
        return toScenarioView(state, updated);
        });
    }

    public ScenarioDto deleteScenario(String scenarioId) {
        return withWriteState(state -> {
        ScenarioDto existing = findScenario(state, scenarioId);
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "SCENARIO", scenarioId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        state.deleteScenario(scenarioId);
        recordCatalogEvent(
            "SCENARIO_DELETED",
            PlatformAggregateType.SCENARIO,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return toScenarioView(state, existing);
        });
    }

    public AssistantDto createAssistant(CreateAssistantRequest request) {
        return withWriteState(state -> {
        AssistantModelPolicyDto normalizedModelPolicy = normalizeAssistantModelPolicy(request.modelPolicy());
        validateAssistantModelPolicy(state, normalizedModelPolicy);
        validatePrivacyModelResourceId(state, request.privacyModelResourceId(), "assistant privacyModelResourceId");
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
        state.upsertAssistant(assistant);
        recordCatalogEvent("ASSISTANT_CREATED", PlatformAggregateType.ASSISTANT, assistant.id(), Map.of("name", assistant.name()));
        return toAssistantView(state, assistant);
        });
    }

    public AssistantDto updateAssistant(String assistantId, UpdateAssistantRequest request) {
        return withWriteState(state -> {
        AssistantDto existing = findAssistant(state, assistantId);
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
        validateAssistantModelPolicy(state, normalizedModelPolicy);
        validatePrivacyModelResourceId(state, privacyModelResourceId, "assistant privacyModelResourceId");
        VersionDto version = new VersionDto(
            effectiveStatus == VersionStatus.PUBLISHED ? nextAssistantReleaseVersion(state, existing.id()) : existing.version().version(),
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
        validateAssistantOwnerConfiguration(state, updated);
        if (effectiveStatus == VersionStatus.PUBLISHED) {
            ensureAssistantReadyForPublication(state, updated);
        }
        AssistantReleaseDto publishedRelease = null;
        state.upsertAssistant(updated);
        if (effectiveStatus == VersionStatus.PUBLISHED) {
            publishedRelease = createAssistantRelease(state, updated.id(), version.version(), VersionStatus.PUBLISHED);
        }
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
        return toAssistantView(state, updated);
        });
    }

    public AssistantDto deleteAssistant(String assistantId) {
        return withWriteState(state -> {
        AssistantDto existing = findAssistant(state, assistantId);
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "ASSISTANT", assistantId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }

        AssistantDto deleted = toAssistantView(state, existing);
        state.deleteAssistant(assistantId);
        state.deleteAssistantReleases(assistantId);
        recordCatalogEvent(
            "ASSISTANT_DELETED",
            PlatformAggregateType.ASSISTANT,
            deleted.id(),
            Map.of("name", deleted.name(), "deletedObjectId", deleted.id(), "deletedObjectName", deleted.name())
        );
        return deleted;
        });
    }

    public List<AssistantDto> listAssistants() {
        return withReadState(this::listAssistants);
    }

    public AssistantDto getAssistant(String assistantId) {
        return withReadState(state -> {
        return toAssistantView(state, findAssistant(state, assistantId));
        });
    }

    public AgentDto createAgent(CreateAgentRequest request) {
        return withWriteState(state -> {
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
        validatePrivacyModelResourceId(state, agent.executionPolicy().privacyModelResourceId(), "agent privacyModelResourceId");
        validateAgentPlaybookReferences(state, agent.assistantId(), agent.playbookIds());
        state.upsertAgent(agent);
        AssistantDto assistant = findAssistant(state, request.assistantId());
        if (assistant.primaryAgentId() != null && !assistant.primaryAgentId().isBlank()) {
            validateAssistantOwnerConfiguration(state, assistant);
        }
        recordCatalogEvent("AGENT_CREATED", PlatformAggregateType.AGENT, agent.id(), Map.of("name", agent.name()));
        return agent;
        });
    }

    public AgentDto updateAgent(String agentId, UpdateAgentRequest request) {
        return withWriteState(state -> {
        AgentDto existing = findAgent(state, agentId);
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
        validatePrivacyModelResourceId(state, updated.executionPolicy().privacyModelResourceId(), "agent privacyModelResourceId");
        validateAgentPlaybookReferences(state, updated.assistantId(), updated.playbookIds());
        state.upsertAgent(updated);
        AssistantDto assistant = findAssistant(state, existing.assistantId());
        if (assistant.primaryAgentId() != null && !assistant.primaryAgentId().isBlank()) {
            validateAssistantOwnerConfiguration(state, assistant);
        }
        recordCatalogEvent("AGENT_UPDATED", PlatformAggregateType.AGENT, updated.id(), Map.of("name", updated.name()));
        return updated;
        });
    }

    public AgentDto deleteAgent(String agentId) {
        return withWriteState(state -> {
        AgentDto existing = findAgent(state, agentId);
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "AGENT", agentId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        state.deleteAgent(agentId);
        clearDeletedPrimaryAgent(state, existing.assistantId(), agentId);
        recordCatalogEvent(
            "AGENT_DELETED",
            PlatformAggregateType.AGENT,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return existing;
        });
    }

    public List<AgentDto> listAgents() {
        return withReadState(this::listAgents);
    }

    public AgentDto getAgent(String agentId) {
        return withReadState(state -> {
        return findAgent(state, agentId);
        });
    }

    public List<PlaybookDto> listPlaybooks() {
        return withReadState(state -> {
        return playbooks(state).stream()
            .sorted(Comparator.comparing(PlaybookDto::name))
            .map(this::normalizePlaybook)
            .toList();
        });
    }

    public PlaybookDto getPlaybook(String playbookId) {
        return withReadState(state -> {
        return normalizePlaybook(findPlaybook(state, playbookId));
        });
    }

    public PlaybookDto createPlaybook(CreatePlaybookRequest request) {
        return withWriteState(state -> {
        findAssistant(state, request.assistantId());
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
        state.upsertPlaybook(playbook);
        recordCatalogEvent("PLAYBOOK_CREATED", PlatformAggregateType.PLAYBOOK, playbook.id(), Map.of("name", playbook.name()));
        return normalizePlaybook(playbook);
        });
    }

    public PlaybookDto updatePlaybook(String playbookId, UpdatePlaybookRequest request) {
        return withWriteState(state -> {
        PlaybookDto existing = findPlaybook(state, playbookId);
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
        state.upsertPlaybook(updated);
        recordCatalogEvent("PLAYBOOK_UPDATED", PlatformAggregateType.PLAYBOOK, updated.id(), Map.of("name", updated.name()));
        return normalizePlaybook(updated);
        });
    }

    public PlaybookDto deletePlaybook(String playbookId) {
        return withWriteState(state -> {
        PlaybookDto existing = findPlaybook(state, playbookId);
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "PLAYBOOK", playbookId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        state.deletePlaybook(playbookId);
        recordCatalogEvent(
            "PLAYBOOK_DELETED",
            PlatformAggregateType.PLAYBOOK,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return normalizePlaybook(existing);
        });
    }

    public ResourceDto createResource(CreateResourceRequest request) {
        return withWriteState(state -> {
        String domainId = requireText(request.domainId(), "resource.domainId");
        findDomain(state, domainId);
        validateResourceOwner(state, domainId, request.ownerType(), request.ownerId());
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
        validateResourceOwner(state, existing.domainId(), request.ownerType(), request.ownerId());
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
        List<StoredResourceVersion> existingVersions = storedVersionsFor(state, resourceId).stream()
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
                version.id().equals(versionId) ? VersionStatus.PUBLISHED : VersionStatus.DRAFT,
                version.summary(),
                version.configDigest(),
                version.createdAt(),
                version.id().equals(versionId) ? Instant.now() : null,
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
        if (resource.effectiveVersion() != null && resource.effectiveVersion().id().equals(versionId)) {
            throw new IllegalStateException("resource effective version cannot be deleted: " + resourceId + "/" + versionId);
        }
        if (versionsFor(state, resourceId).size() <= 1) {
            throw new IllegalStateException("resource must keep at least one version: " + resourceId);
        }

        String versionPinBlocker = findResourceVersionDeletionBlocker(state, knowledgeRepository, resourceId, versionId);
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

    public List<ResourceDto> listResources() {
        return withReadState(this::listResources);
    }

    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return withReadState((state, knowledgeState) -> knowledgeBases(knowledgeState));
    }

    public KnowledgeBaseDto getKnowledgeBase(String knowledgeBaseId) {
        return withReadState((state, knowledgeState) -> toKnowledgeBaseView(knowledgeState, findKnowledgeBase(knowledgeState, knowledgeBaseId)));
    }

    public KnowledgeBaseDto createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        return withWriteState(state -> {
        return knowledgeService.createKnowledgeBase(request);
        });
    }

    public KnowledgeBaseDto updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        return withWriteState(state -> {
        return knowledgeService.updateKnowledgeBase(knowledgeBaseId, request);
        });
    }

    public KnowledgeBaseDto deleteKnowledgeBase(String knowledgeBaseId) {
        return withWriteState(state -> {
        String blocker = findObjectDeletionBlocker(state, knowledgeRepository, "KNOWLEDGE_BASE", knowledgeBaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        return knowledgeService.deleteKnowledgeBaseUnchecked(knowledgeBaseId);
        });
    }

    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return withReadState((state, knowledgeState) -> {
            findKnowledgeBase(knowledgeState, knowledgeBaseId);
            return knowledgeReleases(knowledgeState, knowledgeBaseId);
        });
    }

    public KnowledgeReleaseDto createKnowledgeRelease(String knowledgeBaseId, CreateKnowledgeReleaseRequest request) {
        return withWriteState(state -> {
        return knowledgeService.createKnowledgeRelease(knowledgeBaseId, request);
        });
    }

    public KnowledgeReleaseDto publishKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return withWriteState(state -> {
        return knowledgeService.publishKnowledgeRelease(knowledgeBaseId, releaseId);
        });
    }

    public KnowledgeReleaseDto deleteKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return withWriteState(state -> {
        return knowledgeService.deleteKnowledgeRelease(knowledgeBaseId, releaseId);
        });
    }

    public List<KnowledgeReferenceDto> listKnowledgeReferences(String knowledgeBaseId) {
        return withReadState((state, knowledgeState) ->
            toKnowledgeReferences(objectReferences(state, knowledgeState, "KNOWLEDGE_BASE", knowledgeBaseId))
        );
    }

    public ResourceDto deleteResource(String resourceId) {
        return withWriteState(state -> {
        ResourceDto deleted = toResourceView(state, findResource(state, resourceId));
        String referenceBlocker = findObjectDeletionBlocker(state, knowledgeRepository, "RESOURCE", resourceId);
        if (referenceBlocker != null) {
            throw new IllegalStateException(referenceBlocker);
        }

        state.deleteResource(resourceId);
        state.deleteResourceVersions(resourceId);
        recordCatalogEvent(
            "RESOURCE_DELETED",
            PlatformAggregateType.RESOURCE,
            deleted.id(),
            Map.of("name", deleted.name(), "deletedObjectId", deleted.id(), "deletedObjectName", deleted.name())
        );
        return deleted;
        });
    }

    public KnowledgeUploadSessionDto createKnowledgeUploadSession(String knowledgeBaseId) {
        return withWriteState(state -> {
        return knowledgeService.createUploadSession(knowledgeBaseId);
        });
    }

    public KnowledgeUploadCompletionDto completeKnowledgeUpload(
        String knowledgeBaseId,
        String uploadSessionId,
        String fileName,
        String contentType,
        byte[] payload
    ) {
        return withWriteState(state -> {
        return knowledgeService.completeUpload(knowledgeBaseId, uploadSessionId, fileName, contentType, payload);
        });
    }

    public KnowledgeUploadCompletionDto importKnowledgeUrl(String knowledgeBaseId, CreateKnowledgeUrlImportRequest request) {
        return withWriteState(state -> {
        return knowledgeService.importUrl(knowledgeBaseId, request);
        });
    }

    public List<KnowledgeFileDto> listKnowledgeFiles(String knowledgeBaseId) {
        return withReadState(state -> {
        return knowledgeService.listFiles(knowledgeBaseId);
        });
    }

    public List<KnowledgeImportJobDto> listKnowledgeImportJobs(String knowledgeBaseId) {
        return withReadState(state -> {
        return knowledgeService.listImportJobs(knowledgeBaseId);
        });
    }

    public List<KnowledgeDocumentDto> listKnowledgeDocuments(String knowledgeBaseId) {
        return withReadState(state -> {
        return knowledgeService.listDocuments(knowledgeBaseId);
        });
    }

    public KnowledgeIndexSnapshotDto createKnowledgeIndexSnapshot(String knowledgeBaseId, CreateKnowledgeIndexSnapshotRequest request) {
        return withWriteState(state -> {
        return knowledgeService.createIndexSnapshot(knowledgeBaseId, request);
        });
    }

    public List<KnowledgeIndexSnapshotDto> listKnowledgeIndexSnapshots(String knowledgeBaseId) {
        return withReadState(state -> {
        return knowledgeService.listIndexSnapshots(knowledgeBaseId);
        });
    }

    public ResourceCenterDto resourceCenter() {
        return withReadState(this::resourceCenter);
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
        });
    }

    private void recordCatalogEvent(String eventType, PlatformAggregateType aggregateType, String aggregateId, Map<String, Object> payload) {
        platformEventService.recordControlEvent(eventType, aggregateType, aggregateId, payload);
    }

    private BusinessDomainDto toDomainView(CatalogRepository state, KnowledgeRepository knowledgeState, BusinessDomainDto domain) {
        List<ScenarioDto> domainScenarios = scenarios(state).stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ScenarioDto::name))
            .map(item -> toScenarioView(state, item))
            .toList();
        List<ResourceDto> domainResources = resources(state).stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(item -> toResourceView(state, item))
            .toList();
        List<KnowledgeBaseDto> domainKnowledgeBases = knowledgeBases(knowledgeState).stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .toList();
        return new BusinessDomainDto(domain.id(), domain.name(), domain.description(), domainScenarios, domainResources, domainKnowledgeBases);
    }

    private ScenarioDto toScenarioView(CatalogRepository state, ScenarioDto scenario) {
        List<AssistantDto> scenarioAssistants = assistants(state).stream()
            .filter(item -> item.scenarioId().equals(scenario.id()))
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(item -> toAssistantView(state, item))
            .toList();
        return new ScenarioDto(scenario.id(), scenario.domainId(), scenario.name(), scenario.goal(), scenario.version(), scenarioAssistants);
    }

    private AssistantDto toAssistantView(CatalogRepository state, AssistantDto assistant) {
        List<AgentDto> assistantAgents = orderAgentsForAssistant(state, assistant.id());
        List<AssistantReleaseDto> releases = assistantReleases(state, assistant.id()).stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .toList();
        return new AssistantDto(
            assistant.id(),
            assistant.scenarioId(),
            assistant.name(),
            assistant.description(),
            assistant.version(),
            assistantAgents,
            playbooksForAssistant(state, assistant.id()),
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

    private ResourceDto toResourceView(CatalogRepository state, ResourceDto resource) {
        List<ResourceVersionDto> versions = versionsFor(state, resource.id());
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

    private KnowledgeBaseDto toKnowledgeBaseView(KnowledgeRepository repo, KnowledgeBaseDto knowledgeBase) {
        List<KnowledgeReleaseDto> releases = knowledgeReleases(repo, knowledgeBase.id());
        KnowledgeReleaseDto latestRelease = releases.isEmpty() ? null : releases.getLast();
        KnowledgeReleaseDto effectiveRelease = releases.stream()
            .filter(item -> item.status() == VersionStatus.PUBLISHED)
            .reduce((__, item) -> item)
            .orElse(null);
        return new KnowledgeBaseDto(
            knowledgeBase.id(),
            knowledgeBase.domainId(),
            knowledgeBase.name(),
            knowledgeBase.shareScope(),
            knowledgeBase.ownerType(),
            knowledgeBase.ownerId(),
            knowledgeBase.summary(),
            knowledgeBase.steward(),
            knowledgeBase.tags(),
            latestRelease,
            effectiveRelease,
            releases
        );
    }

    private KnowledgeBaseDto findKnowledgeBase(KnowledgeRepository repo, String knowledgeBaseId) {
        return repo.findKnowledgeBase(knowledgeBaseId)
            .orElseThrow(() -> new NoSuchElementException("knowledge base not found: " + knowledgeBaseId));
    }

    private List<KnowledgeReleaseDto> knowledgeReleases(KnowledgeRepository repo, String knowledgeBaseId) {
        return repo.listKnowledgeReleases(knowledgeBaseId).stream()
            .sorted(Comparator.comparing(KnowledgeReleaseDto::createdAt))
            .toList();
    }

    private List<AgentDto> orderAgentsForAssistant(CatalogRepository state, String assistantId) {
        return agents(state).stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();
    }

    private AssistantReleaseDto createAssistantRelease(CatalogRepository state, String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(state, assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        DefaultModelBindingDto defaultModelBinding = resolveDefaultModelBinding(state, assistant);
        DefaultModelBindingDto privacyModelBinding = resolvePrivacyModelBinding(state, assistant);
        captureEffectiveResource(state, snapshotMap, assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");
        captureEffectiveResource(state, snapshotMap, assistant.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL");
        KnowledgeBindingSnapshotDto assistantKnowledgeBinding = resolveAssistantKnowledgeBinding(assistant);

        List<AssistantReleaseAgentDto> releaseAgents = new ArrayList<>();
        for (AgentDto agent : orderAgentsForAssistant(state, assistantId)) {
            captureEffectiveResource(state, snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());
            String effectivePrivacyModelResourceId = resolveEffectivePrivacyModelResourceId(assistant, agent);
            boolean effectivePrivacyMappingEnabled = resolveEffectivePrivacyMappingEnabled(assistant, agent);
            DefaultModelBindingDto effectivePrivacyModelBinding = effectivePrivacyMappingEnabled
                ? resolvePrivacyModelBinding(state, assistant, agent)
                : null;
            captureEffectiveResource(state, snapshotMap, effectivePrivacyModelResourceId, agent.name() + "_PRIVACY_MODEL");

            List<String> skillResourceVersionIds = new ArrayList<>();
            for (String skillResourceId : agent.executionPolicy().skillResourceIds()) {
                ResourceDto skillResource = toResourceView(state, findResource(state, skillResourceId));
                if (skillResource.type() != ResourceType.SKILL) {
                    throw new IllegalStateException("agent skill must reference SKILL resource: " + agent.name() + " -> " + skillResource.name());
                }
                ResourceVersionDto version = effectiveVersion(state, skillResource);
                skillResourceVersionIds.add(version.id());
                mergeReleaseResource(snapshotMap, skillResource, version, agent.name());
            }
            List<String> toolResourceVersionIds = new ArrayList<>();
            for (String toolResourceId : agent.executionPolicy().toolResourceIds()) {
                ResourceDto toolResource = toResourceView(state, findResource(state, toolResourceId));
                if (toolResource.type() != ResourceType.TOOL) {
                    throw new IllegalStateException("agent tool must reference TOOL resource: " + agent.name() + " -> " + toolResource.name());
                }
                ResourceVersionDto version = effectiveVersion(state, toolResource);
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
            playbooksForAssistant(state, assistantId),
            assistant.primaryAgentId(),
            assistant.ownerPolicy(),
            assistant.sessionPolicy(),
            assistant.replyPolicy(),
            assistant.playbookPolicy(),
            assistant.modelPolicy(),
            assistant.knowledgeAccessPolicy(),
            assistant.memoryPolicy()
        );
        List<AssistantReleaseDto> releases = new ArrayList<>(assistantReleases(state, assistantId));
        releases.add(release);
        state.replaceAssistantReleases(assistantId, releases);
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

    private void captureEffectiveResource(
        CatalogRepository state,
        Map<String, AssistantReleaseResourceDto> snapshotMap,
        String resourceId,
        String boundAgent
    ) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = toResourceView(state, findResource(state, resourceId));
        ResourceVersionDto version = effectiveVersion(state, resource);
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

    private ObjectReferenceAnalysisDto analyzeDomainReferences(CatalogRepository state, KnowledgeRepository knowledgeState, BusinessDomainDto domain) {
        return ObjectReferenceAnalyzer.analyzeDomain(domain, scenarios(state), resources(state), knowledgeBases(knowledgeState));
    }

    private ObjectReferenceAnalysisDto analyzeScenarioReferences(CatalogRepository state, ScenarioDto scenario) {
        return ObjectReferenceAnalyzer.analyzeScenario(scenario, assistants(state));
    }

    private ObjectReferenceAnalysisDto analyzeAssistantReferences(CatalogRepository state, KnowledgeRepository knowledgeState, AssistantDto assistant) {
        return ObjectReferenceAnalyzer.analyzeAssistant(
            toAssistantView(state, assistant),
            agents(state),
            playbooks(state),
            listResources(state),
            knowledgeBases(knowledgeState),
            assistantReleases(state, assistant.id())
        );
    }

    private ObjectReferenceAnalysisDto analyzePlaybookReferences(CatalogRepository state, PlaybookDto playbook) {
        AssistantDto assistant = toAssistantView(state, findAssistant(state, playbook.assistantId()));
        return ObjectReferenceAnalyzer.analyzePlaybook(
            playbook,
            assistant.name(),
            agents(state),
            assistantReleases(state, playbook.assistantId())
        );
    }

    private ObjectReferenceAnalysisDto analyzeAgentReferences(CatalogRepository state, KnowledgeRepository knowledgeState, AgentDto agent) {
        AssistantDto assistant = toAssistantView(state, findAssistant(state, agent.assistantId()));
        return ObjectReferenceAnalyzer.analyzeAgent(
            agent,
            assistant.name(),
            listResources(state),
            knowledgeBases(knowledgeState),
            assistantReleases(state, agent.assistantId())
        );
    }

    private ObjectReferenceAnalysisDto analyzeResourceReferences(CatalogRepository state, ResourceDto resource) {
        return ObjectReferenceAnalyzer.analyzeResource(
            resource,
            repository.findResourceBindings(resource.id()),
            repository.findReleaseResourceRefs(resource.id()),
            sourceKey -> resolveSourceName(state, sourceKey),
            ref -> resolveAssistantReleaseName(state, ref)
        );
    }

    private ObjectReferenceAnalysisDto analyzeKnowledgeBaseReferences(CatalogRepository state, KnowledgeBaseDto knowledgeBase) {
        return ObjectReferenceAnalyzer.analyzeKnowledgeBase(
            knowledgeBase,
            repository.findKnowledgeBindings(knowledgeBase.id()),
            repository.findReleaseKnowledgeRefs(knowledgeBase.id()),
            sourceKey -> resolveSourceName(state, sourceKey),
            releaseId -> resolveAssistantReleaseName(state, releaseId),
            releaseId -> findAssistantReleaseById(state, releaseId)
        );
    }

    private List<ObjectReferenceRelationDto> relationsByImpactLevel(ObjectReferenceAnalysisDto analysis, String impactLevel) {
        return analysis.relations().stream()
            .filter(relation -> impactLevel.equals(relation.impactLevel()))
            .toList();
    }

    private List<DeletionCascadeItemDto> buildCascadeDeletes(
        CatalogRepository state,
        KnowledgeRepository knowledgeState,
        String objectType,
        String objectId
    ) {
        return switch (objectType) {
            case "DOMAIN", "SCENARIO" -> List.of();
            case "ASSISTANT" -> buildAssistantCascadeDeletes(state, objectId);
            case "PLAYBOOK", "AGENT" -> List.of();
            case "RESOURCE" -> buildResourceCascadeDeletes(state, objectId);
            case "KNOWLEDGE_BASE" -> buildKnowledgeBaseCascadeDeletes(knowledgeState, objectId);
            default -> throw new IllegalArgumentException("unsupported reference object type: " + objectType);
        };
    }

    private List<DeletionCascadeItemDto> buildAssistantCascadeDeletes(CatalogRepository state, String assistantId) {
        List<DeletionCascadeItemDto> cascadeDeletes = new ArrayList<>();
        AssistantDto assistant = toAssistantView(state, findAssistant(state, assistantId));
        assistantReleases(state, assistantId).stream()
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

    private List<DeletionCascadeItemDto> buildResourceCascadeDeletes(CatalogRepository state, String resourceId) {
        ResourceDto resource = toResourceView(state, findResource(state, resourceId));
        return versionsFor(state, resourceId).stream()
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

    private List<DeletionCascadeItemDto> buildKnowledgeBaseCascadeDeletes(KnowledgeRepository knowledgeState, String knowledgeBaseId) {
        KnowledgeBaseDto knowledgeBase = toKnowledgeBaseView(knowledgeState, findKnowledgeBase(knowledgeState, knowledgeBaseId));
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

    private String findObjectDeletionBlocker(CatalogRepository state, KnowledgeRepository knowledgeState, String objectType, String objectId) {
        ObjectReferenceAnalysisDto analysis = objectReferences(state, knowledgeState, objectType, objectId);
        return relationsByImpactLevel(analysis, "BLOCKS_DELETION").stream()
            .map(relation -> toDeletionMessage(analysis.objectType(), relation))
            .findFirst()
            .orElse(null);
    }

    private String findResourceVersionDeletionBlocker(CatalogRepository state, KnowledgeRepository knowledgeState, String resourceId, String versionId) {
        return objectReferences(state, knowledgeState, "RESOURCE", resourceId).relations().stream()
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

    private String resolveSourceName(CatalogRepository state, String sourceKey) {
        int separatorIndex = sourceKey.indexOf(':');
        if (separatorIndex < 0) {
            return sourceKey;
        }
        return resolveSourceName(state, sourceKey.substring(0, separatorIndex), sourceKey.substring(separatorIndex + 1));
    }

    private String resolveSourceName(CatalogRepository state, String sourceType, String sourceId) {
        return switch (sourceType) {
            case "ASSISTANT" -> assistants(state).stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AssistantDto::name).orElse(sourceId);
            case "AGENT" -> agents(state).stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AgentDto::name).orElse(sourceId);
            default -> sourceId;
        };
    }

    private AssistantReleaseDto findAssistantReleaseById(CatalogRepository state, String releaseId) {
        return state.findAssistantReleaseById(releaseId).orElse(null);
    }

    private String resolveAssistantReleaseName(CatalogRepository state, CatalogRepository.ReleaseResourceRef ref) {
        AssistantDto assistant = toAssistantView(state, findAssistant(state, ref.assistantId()));
        return assistant.name() + "@" + findReleaseVersion(state, ref.assistantId(), ref.releaseId());
    }

    private String resolveAssistantReleaseName(CatalogRepository state, String releaseId) {
        AssistantReleaseDto release = findAssistantReleaseById(state, releaseId);
        if (release == null) {
            return releaseId;
        }
        AssistantDto assistant = toAssistantView(state, findAssistant(state, release.assistantId()));
        return assistant.name() + "@" + release.releaseVersion();
    }

    private String findReleaseVersion(CatalogRepository state, String assistantId, String releaseId) {
        List<AssistantReleaseDto> releases = assistantReleases(state, assistantId);
        if (releases.isEmpty()) return releaseId;
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

    private AgentDto normalizeLoadedAgent(CatalogRepository state, AgentDto agent) {
        AgentExecutionPolicyDto normalizedPolicy = normalizeAgentExecutionPolicy(agent.executionPolicy());
        List<String> enabledSkillResourceIds = normalizedPolicy.skillResourceIds().stream()
            .filter(resourceId -> resources(state).stream().anyMatch(item -> item.id().equals(resourceId) && item.type() == ResourceType.SKILL))
            .toList();
        List<String> enabledToolResourceIds = normalizedPolicy.toolResourceIds().stream()
            .filter(resourceId -> resources(state).stream().anyMatch(item -> item.id().equals(resourceId) && item.type() == ResourceType.TOOL))
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

    private List<PlaybookDto> playbooksForAssistant(CatalogRepository state, String assistantId) {
        return playbooks(state).stream()
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

    private void validatePlaybookDefinition(PlaybookDto playbook) {
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
            if (node.nodeType() == com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.STEP) {
                validateStepNode(node);
            }
            if (node.nodeType() == com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.TOOL_TASK) {
                validateToolTaskNode(node);
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

    private BusinessDomainDto findDomain(CatalogRepository state, String domainId) {
        return domains(state).stream()
            .filter(item -> item.id().equals(domainId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("business domain not found: " + domainId));
    }

    private ScenarioDto findScenario(CatalogRepository state, String scenarioId) {
        return scenarios(state).stream()
            .filter(item -> item.id().equals(scenarioId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("scenario not found: " + scenarioId));
    }

    private ResourceDto findResource(CatalogRepository state, String resourceId) {
        return resources(state).stream()
            .filter(item -> item.id().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("resource not found: " + resourceId));
    }

    private AssistantDto findAssistant(CatalogRepository state, String assistantId) {
        return assistants(state).stream()
            .filter(item -> item.id().equals(assistantId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("assistant not found: " + assistantId));
    }

    private AgentDto findAgent(CatalogRepository state, String agentId) {
        return agents(state).stream()
            .filter(item -> item.id().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("agent not found: " + agentId));
    }

    private PlaybookDto findPlaybook(CatalogRepository state, String playbookId) {
        return playbooks(state).stream()
            .filter(item -> item.id().equals(playbookId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("playbook not found: " + playbookId));
    }

    private void validateAgentPlaybookReferences(CatalogRepository state, String assistantId, List<String> playbookIds) {
        if (playbookIds == null || playbookIds.isEmpty()) {
            return;
        }
        Set<String> allowedPlaybooks = playbooks(state).stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .map(PlaybookDto::id)
            .collect(java.util.stream.Collectors.toSet());
        for (String playbookId : playbookIds) {
            if (!allowedPlaybooks.contains(playbookId)) {
                throw new IllegalStateException("agent references unknown playbook: " + playbookId);
            }
        }
    }

    private ResourceVersionDto findResourceVersion(CatalogRepository state, String resourceId, String versionId) {
        return versionsFor(state, resourceId).stream()
            .filter(item -> item.id().equals(versionId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("resource version not found: " + resourceId + "/" + versionId));
    }

    private List<ResourceVersionDto> versionsFor(CatalogRepository state, String resourceId) {
        return resourceVersions(state, resourceId).stream()
            .sorted(Comparator.comparing(StoredResourceVersion::createdAt))
            .map(this::toResourceVersionDto)
            .toList();
    }

    private List<StoredResourceVersion> storedVersionsFor(CatalogRepository state, String resourceId) {
        return resourceVersions(state, resourceId).stream()
            .sorted(Comparator.comparing(StoredResourceVersion::createdAt))
            .toList();
    }

    private ResourceVersionDto effectiveVersion(CatalogRepository state, ResourceDto resource) {
        return versionsFor(state, resource.id()).stream()
            .filter(item -> item.status() == VersionStatus.PUBLISHED)
            .reduce((__, item) -> item)
            .orElseGet(() -> versionsFor(state, resource.id()).stream().findFirst().orElseThrow());
    }

    private String nextAssistantReleaseVersion(CatalogRepository state, String assistantId) {
        List<AssistantReleaseDto> releases = assistantReleases(state, assistantId).stream()
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
        Map<String, Object> defaultConfig = new LinkedHashMap<>();
        defaultConfig.put("baseUrl", "http://localhost:8081");
        Map<String, Object> defaultMapping = new LinkedHashMap<>();
        defaultMapping.put("method", "POST");
        defaultMapping.put("path", "/tools/invoke");
        defaultMapping.put("requestPlacement", "JSON_BODY");
        return new ToolConfigDto(
            List.of(new ToolOperationDto("invoke", "执行通用工具动作", "{\"input\":\"string\"}", "{\"type\":\"object\",\"required\":[\"output\"],\"properties\":{\"output\":{\"type\":\"string\"}},\"additionalProperties\":false}")),
            new ToolConnectorConfigDto(
                ToolConnectorType.SIMPLE_HTTP,
                null,
                15,
                "NONE",
                Map.copyOf(defaultConfig),
                Map.of("invoke", Map.copyOf(defaultMapping))
            )
        );
    }

    private ToolConfigDto normalizeToolConfig(ToolConfigDto configuration) {
        ToolConfigDto defaults = defaultToolConfig();
        List<ToolOperationDto> operations = normalizeToolOperations(configuration == null ? null : configuration.operations());
        if (operations.isEmpty()) {
            operations = defaults.operations();
        }

        return new ToolConfigDto(
            List.copyOf(operations),
            normalizeToolConnector(configuration == null ? null : configuration.connector(), operations)
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

    private ToolConnectorConfigDto normalizeToolConnector(ToolConnectorConfigDto configuration, List<ToolOperationDto> operations) {
        ToolConnectorConfigDto defaults = defaultToolConfig().connector();
        ToolConnectorType connectorType = configuration == null || configuration.connectorType() == null
            ? defaults.connectorType()
            : configuration.connectorType();
        Map<String, Object> config = new LinkedHashMap<>(configuration == null || configuration.config() == null ? Map.of() : configuration.config());
        if (connectorType == ToolConnectorType.SIMPLE_HTTP || connectorType == ToolConnectorType.BUSINESS_CODE_SECRET_HTTP) {
            config.putIfAbsent("baseUrl", defaults.config().get("baseUrl"));
        }
        if (connectorType == ToolConnectorType.MCP) {
            config.putIfAbsent("serverName", "default-mcp-server");
            config.putIfAbsent("transport", "STREAMABLE_HTTP");
            config.putIfAbsent("connectionUri", "http://localhost:8081/mcp");
            config.putIfAbsent("namespace", "default.namespace");
            config.putIfAbsent("heartbeatSeconds", 30);
            config.putIfAbsent("internalAuthEnabled", false);
        }
        String accountId = normalizeOptionalText(configuration == null ? null : configuration.accountId());
        if (connectorType == ToolConnectorType.BUSINESS_CODE_SECRET_HTTP && accountId.isBlank()) {
            throw new IllegalArgumentException("BUSINESS_CODE_SECRET_HTTP connector requires accountId");
        }
        Map<String, Map<String, Object>> operationMappings = new LinkedHashMap<>();
        Map<String, Map<String, Object>> requestedMappings = configuration == null || configuration.operationMappings() == null
            ? Map.of()
            : configuration.operationMappings();
        for (ToolOperationDto operation : operations) {
            Map<String, Object> mapping = new LinkedHashMap<>(requestedMappings.getOrDefault(operation.name(), Map.of()));
            if (connectorType == ToolConnectorType.SIMPLE_HTTP || connectorType == ToolConnectorType.BUSINESS_CODE_SECRET_HTTP) {
                mapping.putIfAbsent("method", "POST");
                mapping.putIfAbsent("path", "/tools/" + operation.name());
                mapping.putIfAbsent("requestPlacement", "JSON_BODY");
            }
            if (connectorType == ToolConnectorType.MCP) {
                mapping.putIfAbsent("tool", operation.name());
            }
            operationMappings.put(operation.name(), Map.copyOf(mapping));
        }
        return new ToolConnectorConfigDto(
            connectorType,
            accountId,
            configuration == null || configuration.timeoutSeconds() <= 0 ? defaults.timeoutSeconds() : configuration.timeoutSeconds(),
            configuration == null || configuration.retryPolicy() == null || configuration.retryPolicy().isBlank() ? defaults.retryPolicy() : configuration.retryPolicy(),
            Map.copyOf(config),
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

    private void validateResourceOwner(CatalogRepository state, String domainId, String ownerType, String ownerId) {
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
        AssistantDto assistant = findAssistant(state, normalizedOwnerId);
        ScenarioDto scenario = findScenario(state, assistant.scenarioId());
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

    private void validateAssistantModelPolicy(CatalogRepository state, AssistantModelPolicyDto policy) {
        if (policy == null || policy.defaultModelResourceId() == null || policy.defaultModelResourceId().isBlank()) {
            return;
        }
        ResourceDto resource = findResource(state, policy.defaultModelResourceId());
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException("assistant default model must reference an LLM_MODEL resource");
        }
    }

    private void validatePrivacyModelResourceId(CatalogRepository state, String resourceId, String fieldName) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = findResource(state, resourceId);
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException(fieldName + " must reference an LLM_MODEL resource");
        }
    }

    private void validateAssistantOwnerConfiguration(CatalogRepository state, AssistantDto assistant) {
        String primaryAgentId = assistant.primaryAgentId();
        if (primaryAgentId == null || primaryAgentId.isBlank()) {
            return;
        }
        AgentDto primaryAgent = agents(state).stream()
            .filter(item -> item.assistantId().equals(assistant.id()))
            .filter(item -> item.id().equals(primaryAgentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("assistant primaryAgentId must reference an existing agent under the same assistant"));
        if (!primaryAgent.canOwnSession()) {
            throw new IllegalArgumentException("assistant primaryAgentId must reference an agent with canOwnSession=true");
        }
    }

    private void clearDeletedPrimaryAgent(CatalogRepository state, String assistantId, String deletedAgentId) {
        AssistantDto assistant = findAssistant(state, assistantId);
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
        state.upsertAssistant(updated);
    }

    private void ensureAssistantReadyForPublication(CatalogRepository state, AssistantDto assistant) {
        AssistantModelPolicyDto policy = assistant.modelPolicy();
        if (policy == null || policy.defaultModelResourceId() == null || policy.defaultModelResourceId().isBlank()) {
            throw new IllegalStateException("assistant default model must be configured before publishing");
        }
        if (assistant.primaryAgentId() == null || assistant.primaryAgentId().isBlank()) {
            throw new IllegalStateException("assistant primaryAgentId must be configured before publishing");
        }
        validateAssistantOwnerConfiguration(state, assistant);
        if (assistant.privacyMappingEnabled()) {
            resolvePrivacyModelBinding(state, assistant);
        }
        for (AgentDto agent : orderAgentsForAssistant(state, assistant.id())) {
            if (!resolveEffectivePrivacyMappingEnabled(assistant, agent)) {
                continue;
            }
            resolvePrivacyModelBinding(state, assistant, agent);
        }
    }

    private DefaultModelBindingDto resolveDefaultModelBinding(CatalogRepository state, AssistantDto assistant) {
        String resourceId = assistant.modelPolicy().defaultModelResourceId();
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("assistant default model must be configured before publishing");
        }
        return resolveLlmModelBinding(state, resourceId, false, "assistant default model");
    }

    private DefaultModelBindingDto resolvePrivacyModelBinding(CatalogRepository state, AssistantDto assistant) {
        if (!assistant.privacyMappingEnabled()) {
            return null;
        }
        String resourceId = normalizeOptionalText(assistant.privacyModelResourceId());
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("assistant privacy model must be configured when privacy mapping is enabled");
        }
        return resolveLlmModelBinding(state, resourceId, true, "assistant privacy model");
    }

    private DefaultModelBindingDto resolvePrivacyModelBinding(CatalogRepository state, AssistantDto assistant, AgentDto agent) {
        String resourceId = resolveEffectivePrivacyModelResourceId(assistant, agent);
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalStateException("effective privacy model must be configured when privacy mapping is enabled");
        }
        return resolveLlmModelBinding(state, resourceId, true, "effective privacy model");
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

    private DefaultModelBindingDto resolveLlmModelBinding(
        CatalogRepository state,
        String resourceId,
        boolean requirePrivateDeployment,
        String usageLabel
    ) {
        ResourceDto resource = toResourceView(state, findResource(state, resourceId));
        if (resource.type() != ResourceType.LLM_MODEL) {
            throw new IllegalArgumentException(usageLabel + " must reference an LLM_MODEL resource");
        }
        ResourceVersionDto version = effectiveVersion(state, resource);
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

    private void ensureUniqueDomainName(CatalogRepository state, String name, String excludedDomainId) {
        boolean exists = domains(state).stream().anyMatch(item ->
            !item.id().equals(excludedDomainId)
                && item.name().equalsIgnoreCase(name)
        );
        if (exists) {
            throw new IllegalArgumentException("business domain name already exists: " + name);
        }
    }

    private void ensureUniqueScenarioName(CatalogRepository state, String domainId, String name, String excludedScenarioId) {
        boolean exists = scenarios(state).stream().anyMatch(item ->
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

    private static final class NoOpKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        @Override
        public void startImport(String knowledgeBaseId, String importJobId) {
        }

        @Override
        public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
        }
    }
}
