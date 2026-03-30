package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.knowledge.InMemoryKnowledgeRepository;
import com.lynxus.platform.knowledge.KnowledgeService;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.contracts.runtime.WorkflowContracts.OrchestrationNodeType;
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
    private boolean initialized;
    private final List<BusinessDomainDto> domains = new ArrayList<>();
    private final List<ScenarioDto> scenarios = new ArrayList<>();
    private final List<AssistantDto> assistants = new ArrayList<>();
    private final List<AgentDto> agents = new ArrayList<>();
    private final List<ResourceDto> resources = new ArrayList<>();
    private final Map<String, List<StoredResourceVersion>> resourceVersions = new LinkedHashMap<>();
    private final Map<String, List<AssistantReleaseDto>> assistantReleases = new LinkedHashMap<>();
    private final Map<String, AssistantOrchestrationDto> orchestrations = new LinkedHashMap<>();

    public CatalogService() {
        this(
            new InMemoryCatalogRepository(),
            new InMemoryKnowledgeRepository(),
            new KnowledgeServiceClient("http://localhost:8091"),
            new NoOpKnowledgeWorkflowGateway()
        );
    }

    @Autowired
    public CatalogService(CatalogRepository repository, KnowledgeService knowledgeService) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
    }

    public CatalogService(CatalogRepository repository, KnowledgeServiceClient knowledgeServiceClient, KnowledgeWorkflowGateway knowledgeWorkflowGateway) {
        this(repository, new InMemoryKnowledgeRepository(), knowledgeServiceClient, knowledgeWorkflowGateway);
    }

    CatalogService(
        CatalogRepository repository,
        com.lynxus.platform.knowledge.KnowledgeRepository knowledgeRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway
    ) {
        this.repository = repository;
        this.knowledgeService = new KnowledgeService(knowledgeRepository, repository, knowledgeServiceClient, knowledgeWorkflowGateway);
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
            listOrchestrations(),
            resourceCenter(),
            resourceBlueprints()
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
        return toDomainView(updated);
    }

    public BusinessDomainDto deleteDomain(String domainId) {
        ensureLoaded();
        BusinessDomainDto existing = findDomain(domainId);
        if (scenarios.stream().anyMatch(item -> item.domainId().equals(domainId))) {
            throw new IllegalStateException("business domain still contains scenarios: " + domainId);
        }
        if (resources.stream().anyMatch(item -> item.domainId().equals(domainId))) {
            throw new IllegalStateException("business domain still contains resources: " + domainId);
        }
        if (knowledgeService.hasKnowledgeBasesInDomain(domainId)) {
            throw new IllegalStateException("business domain still contains knowledge bases: " + domainId);
        }
        domains.removeIf(item -> item.id().equals(domainId));
        persistState();
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
        return toScenarioView(updated);
    }

    public ScenarioDto deleteScenario(String scenarioId) {
        ensureLoaded();
        ScenarioDto existing = findScenario(scenarioId);
        if (assistants.stream().anyMatch(item -> item.scenarioId().equals(scenarioId))) {
            throw new IllegalStateException("scenario still contains assistants: " + scenarioId);
        }
        scenarios.removeIf(item -> item.id().equals(scenarioId));
        persistState();
        return toScenarioView(existing);
    }

    public AssistantDto createAssistant(CreateAssistantRequest request) {
        ensureLoaded();
        AssistantDto assistant = new AssistantDto(
            nextId("assistant"),
            request.scenarioId(),
            request.name(),
            request.description(),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of(),
            null,
            List.of(),
            normalizeAssistantModelPolicy(request.modelPolicy()),
            normalizeRagPolicy(request.ragPolicy()),
            normalizeMemoryPolicy(request.memoryPolicy())
        );
        assistants.add(assistant);
        persistState();
        return toAssistantView(assistant);
    }

    public AssistantDto updateAssistant(String assistantId, UpdateAssistantRequest request) {
        ensureLoaded();
        AssistantDto existing = findAssistant(assistantId);
        VersionDto version = new VersionDto(
            request.status() == VersionStatus.PUBLISHED ? nextAssistantReleaseVersion(existing.id()) : existing.version().version(),
            request.status() == null ? existing.version().status() : request.status(),
            Instant.now()
        );
        AssistantDto updated = new AssistantDto(
            existing.id(),
            existing.scenarioId(),
            request.name(),
            request.description(),
            version,
            existing.agents(),
            existing.currentRelease(),
            existing.releases(),
            normalizeAssistantModelPolicy(request.modelPolicy()),
            normalizeRagPolicy(request.ragPolicy()),
            normalizeMemoryPolicy(request.memoryPolicy())
        );
        replace(assistants, AssistantDto::id, updated);
        if (request.status() == VersionStatus.PUBLISHED) {
            createAssistantRelease(updated.id(), version.version(), VersionStatus.PUBLISHED);
        }
        persistState();
        return toAssistantView(updated);
    }

    public AssistantDto deleteAssistant(String assistantId) {
        ensureLoaded();
        AssistantDto existing = findAssistant(assistantId);
        if (agents.stream().anyMatch(item -> item.assistantId().equals(assistantId))) {
            throw new IllegalStateException("assistant still contains agents: " + assistantId);
        }
        if (resources.stream().anyMatch(item -> "ASSISTANT".equals(item.ownerType()) && assistantId.equals(item.ownerId()))) {
            throw new IllegalStateException("assistant still owns resources: " + assistantId);
        }
        if (knowledgeService.hasAssistantOwnedKnowledgeBases(assistantId)) {
            throw new IllegalStateException("assistant still owns knowledge bases: " + assistantId);
        }

        AssistantDto deleted = toAssistantView(existing);
        assistants.removeIf(item -> item.id().equals(assistantId));
        assistantReleases.remove(assistantId);
        orchestrations.remove(assistantId);
        persistState();
        return deleted;
    }

    public List<AssistantDto> listAssistants() {
        ensureLoaded();
        return assistants.stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(this::toAssistantView)
            .toList();
    }

    public AgentDto createAgent(CreateAgentRequest request) {
        ensureLoaded();
        AgentDto agent = new AgentDto(
            nextId("agent"),
            request.assistantId(),
            request.name(),
            request.role(),
            request.responsibility(),
            normalizeAgentExecutionPolicy(request.executionPolicy())
        );
        agents.add(agent);
        persistState();
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
            normalizeAgentExecutionPolicy(request.executionPolicy())
        );
        replace(agents, AgentDto::id, updated);
        persistState();
        return updated;
    }

    public AgentDto deleteAgent(String agentId) {
        ensureLoaded();
        AgentDto existing = findAgent(agentId);
        agents.removeIf(item -> item.id().equals(agentId));
        recycleOrchestrationAfterAgentDeletion(existing.assistantId(), agentId);
        persistState();
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
        return updatedVersions.stream()
            .filter(item -> item.id().equals(versionId))
            .map(this::toResourceVersionDto)
            .findFirst()
            .orElseThrow();
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
        return updatedVersions.stream()
            .filter(item -> item.id().equals(versionId))
            .map(this::toResourceVersionDto)
            .findFirst()
            .orElseThrow();
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

        String versionPinBlocker = findResourceVersionDeletionBlocker(versionId);
        if (versionPinBlocker != null) {
            throw new IllegalStateException(versionPinBlocker);
        }

        List<StoredResourceVersion> updatedVersions = storedVersionsFor(resourceId).stream()
            .filter(item -> !item.id().equals(versionId))
            .toList();
        resourceVersions.put(resourceId, updatedVersions);
        persistState();
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
        return knowledgeService.deleteKnowledgeBase(knowledgeBaseId);
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
        return knowledgeService.listKnowledgeReferences(knowledgeBaseId);
    }

    public ResourceDto deleteResource(String resourceId) {
        ensureLoaded();
        ResourceDto deleted = toResourceView(findResource(resourceId));
        String referenceBlocker = findResourceDeletionBlocker(resourceId);
        if (referenceBlocker != null) {
            throw new IllegalStateException(referenceBlocker);
        }

        resources.removeIf(item -> item.id().equals(resourceId));
        resourceVersions.remove(resourceId);
        persistState();
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

    public List<AssistantOrchestrationDto> listOrchestrations() {
        ensureLoaded();
        return assistants.stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(assistant -> getOrCreateOrchestration(assistant.id()))
            .toList();
    }

    public AssistantOrchestrationDto getOrchestration(String assistantId) {
        ensureLoaded();
        findAssistant(assistantId);
        return getOrCreateOrchestration(assistantId);
    }

    public AssistantOrchestrationDto saveOrchestration(String assistantId, UpdateOrchestrationRequest request) {
        ensureLoaded();
        AssistantDto assistant = findAssistant(assistantId);
        AssistantOrchestrationDto saved = synchronizeOrchestration(new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            request.executionMode(),
            request.nodes(),
            request.edges()
        ));
        validateOrchestration(saved);
        orchestrations.put(assistantId, saved);
        persistState();
        return saved;
    }

    public ResourceCenterDto resourceCenter() {
        ensureLoaded();
        List<ResourceReferenceDto> references = resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .flatMap(resource -> listResourceReferences(resource).stream())
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
            releases.isEmpty() ? null : releases.getFirst(),
            releases,
            normalizeAssistantModelPolicy(assistant.modelPolicy()),
            normalizeRagPolicy(assistant.ragPolicy()),
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
        AssistantOrchestrationDto orchestration = orchestrations.get(assistantId);
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        if (orchestration != null) {
            int index = 0;
            for (OrchestrationNodeDto node : orchestration.nodes()) {
                if (node.agentId() != null && !node.agentId().isBlank()) {
                    orderIndex.putIfAbsent(node.agentId(), index++);
                }
            }
        }
        return agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE)).thenComparing(AgentDto::name))
            .toList();
    }

    private AssistantOrchestrationDto getOrCreateOrchestration(String assistantId) {
        AssistantOrchestrationDto current = orchestrations.computeIfAbsent(assistantId, this::buildDefaultOrchestration);
        AssistantOrchestrationDto synced = synchronizeOrchestration(current);
        orchestrations.put(assistantId, synced);
        return synced;
    }

    private AssistantOrchestrationDto buildDefaultOrchestration(String assistantId) {
        AssistantDto assistant = findAssistant(assistantId);
        List<AgentDto> assistantAgents = agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();

        List<OrchestrationNodeDto> nodes = new ArrayList<>();
        nodes.add(new OrchestrationNodeDto("start", "开始", OrchestrationNodeType.START, "接收用户消息。", null, null));
        assistantAgents.forEach(agent -> nodes.add(toNode(agent)));
        nodes.add(new OrchestrationNodeDto("end", "结束", OrchestrationNodeType.END, "流程结束。", null, null));

        return new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            "GRAPH",
            nodes,
            buildSequentialEdges(nodes)
        );
    }

    private AssistantOrchestrationDto synchronizeOrchestration(AssistantOrchestrationDto source) {
        AssistantDto assistant = findAssistant(source.assistantId());
        Map<String, OrchestrationNodeDto> existingAgentNodes = new LinkedHashMap<>();
        List<OrchestrationNodeDto> syncedNodes = new ArrayList<>();

        for (OrchestrationNodeDto node : source.nodes()) {
            if (node.nodeType() == OrchestrationNodeType.AGENT && node.agentId() != null && !node.agentId().isBlank()) {
                existingAgentNodes.put(node.agentId(), node);
                continue;
            }
            syncedNodes.add(node);
        }

        List<AgentDto> assistantAgents = orderAgentsForSavedNodes(assistant.id(), existingAgentNodes);
        List<OrchestrationNodeDto> rebuiltAgentNodes = assistantAgents.stream()
            .map(agent -> {
                OrchestrationNodeDto existing = existingAgentNodes.get(agent.id());
                if (existing == null) {
                    return toNode(agent);
                }
                return new OrchestrationNodeDto(
                    existing.nodeKey(),
                    agent.name(),
                    OrchestrationNodeType.AGENT,
                    existing.description() == null || existing.description().isBlank() ? agent.responsibility() : existing.description(),
                    agent.id(),
                    null
                );
            })
            .toList();

        List<OrchestrationNodeDto> finalNodes = new ArrayList<>();
        boolean insertedAgents = false;
        for (OrchestrationNodeDto node : syncedNodes) {
            if (!insertedAgents && node.nodeType() == OrchestrationNodeType.END) {
                finalNodes.addAll(rebuiltAgentNodes);
                insertedAgents = true;
            }
            finalNodes.add(node);
        }
        if (!insertedAgents) {
            finalNodes.addAll(rebuiltAgentNodes);
        }

        Map<String, OrchestrationNodeDto> nodesByKey = finalNodes.stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.nodeKey(), item), Map::putAll);
        List<OrchestrationEdgeDto> edges = source.edges().stream()
            .filter(edge -> nodesByKey.containsKey(edge.sourceNodeKey()) && nodesByKey.containsKey(edge.targetNodeKey()))
            .toList();
        if (edges.isEmpty() && finalNodes.size() > 1) {
            edges = buildSequentialEdges(finalNodes);
        }

        return new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            source.executionMode(),
            finalNodes,
            edges
        );
    }

    private void recycleOrchestrationAfterAgentDeletion(String assistantId, String agentId) {
        AssistantOrchestrationDto current = orchestrations.get(assistantId);
        if (current == null) {
            return;
        }

        List<OrchestrationNodeDto> remainingNodes = current.nodes().stream()
            .filter(node -> !(node.nodeType() == OrchestrationNodeType.AGENT && agentId.equals(node.agentId())))
            .toList();
        Set<String> remainingNodeKeys = remainingNodes.stream()
            .map(OrchestrationNodeDto::nodeKey)
            .collect(HashSet::new, Set::add, Set::addAll);
        List<OrchestrationEdgeDto> remainingEdges = current.edges().stream()
            .filter(edge -> remainingNodeKeys.contains(edge.sourceNodeKey()) && remainingNodeKeys.contains(edge.targetNodeKey()))
            .toList();

        AssistantOrchestrationDto candidate = synchronizeOrchestration(new AssistantOrchestrationDto(
            assistantId,
            current.assistantName(),
            current.scenarioId(),
            current.executionMode(),
            remainingNodes,
            remainingEdges
        ));
        try {
            validateOrchestration(candidate);
            orchestrations.put(assistantId, candidate);
        } catch (IllegalArgumentException ignored) {
            orchestrations.put(assistantId, buildDefaultOrchestration(assistantId));
        }
    }

    private List<AgentDto> orderAgentsForSavedNodes(String assistantId, Map<String, OrchestrationNodeDto> existingNodes) {
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        int index = 0;
        for (String agentId : existingNodes.keySet()) {
            orderIndex.put(agentId, index++);
        }
        return agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator.comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE)).thenComparing(AgentDto::name))
            .toList();
    }

    private void validateOrchestration(AssistantOrchestrationDto orchestration) {
        List<OrchestrationNodeDto> nodes = orchestration.nodes();
        List<OrchestrationEdgeDto> edges = orchestration.edges();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("orchestration must define nodes");
        }

        Map<String, OrchestrationNodeDto> nodesByKey = nodes.stream()
            .collect(LinkedHashMap::new, (map, item) -> {
                if (map.put(item.nodeKey(), item) != null) {
                    throw new IllegalArgumentException("duplicate nodeKey: " + item.nodeKey());
                }
            }, Map::putAll);
        long startCount = nodes.stream().filter(node -> node.nodeType() == OrchestrationNodeType.START).count();
        long endCount = nodes.stream().filter(node -> node.nodeType() == OrchestrationNodeType.END).count();
        if (startCount != 1 || endCount < 1) {
            throw new IllegalArgumentException("orchestration must contain exactly one START node and at least one END node");
        }

        Set<String> assistantAgentIds = agents.stream()
            .filter(agent -> agent.assistantId().equals(orchestration.assistantId()))
            .map(AgentDto::id)
            .collect(HashSet::new, Set::add, Set::addAll);
        for (OrchestrationNodeDto node : nodes) {
            if (node.nodeType() == OrchestrationNodeType.AGENT && (node.agentId() == null || !assistantAgentIds.contains(node.agentId()))) {
                throw new IllegalArgumentException("agent node references unknown agent: " + node.agentId());
            }
            if (node.nodeType() == OrchestrationNodeType.HUMAN && node.humanNode() == null) {
                throw new IllegalArgumentException("human node requires humanNode config: " + node.nodeKey());
            }
        }

        Map<String, List<OrchestrationEdgeDto>> outgoing = new LinkedHashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        for (OrchestrationEdgeDto edge : edges) {
            if (!edgeKeys.add(edge.edgeKey())) {
                throw new IllegalArgumentException("duplicate edgeKey: " + edge.edgeKey());
            }
            if (!nodesByKey.containsKey(edge.sourceNodeKey()) || !nodesByKey.containsKey(edge.targetNodeKey())) {
                throw new IllegalArgumentException("edge references missing nodes: " + edge.edgeKey());
            }
            if (edge.routeKey() == null || edge.routeKey().isBlank()) {
                throw new IllegalArgumentException("edge routeKey is required: " + edge.edgeKey());
            }
            outgoing.computeIfAbsent(edge.sourceNodeKey(), __ -> new ArrayList<>()).add(edge);
        }

        for (OrchestrationNodeDto node : nodes) {
            List<OrchestrationEdgeDto> nodeEdges = outgoing.getOrDefault(node.nodeKey(), List.of());
            if (node.nodeType() != OrchestrationNodeType.END && nodeEdges.isEmpty()) {
                throw new IllegalArgumentException("node has no outgoing edges: " + node.nodeKey());
            }
            if (node.nodeType() == OrchestrationNodeType.START) {
                if (nodeEdges.size() != 1) {
                    throw new IllegalArgumentException("START node must have exactly one outgoing edge: " + node.nodeKey());
                }
                OrchestrationEdgeDto startEdge = nodeEdges.getFirst();
                if (!startEdge.defaultEdge() || !"default".equals(startEdge.routeKey())) {
                    throw new IllegalArgumentException("START node outgoing edge must be routeKey=default and defaultEdge=true: " + startEdge.edgeKey());
                }
            }
            Set<String> routeKeys = new HashSet<>();
            for (OrchestrationEdgeDto edge : nodeEdges) {
                if (edge.defaultEdge() && !"default".equals(edge.routeKey())) {
                    throw new IllegalArgumentException("default edge must use routeKey=default: " + edge.edgeKey());
                }
                if (!edge.defaultEdge() && "default".equals(edge.routeKey())) {
                    throw new IllegalArgumentException("non-default edge cannot use routeKey=default: " + edge.edgeKey());
                }
                if (!routeKeys.add(edge.routeKey())) {
                    throw new IllegalArgumentException("duplicate routeKey for node " + node.nodeKey() + ": " + edge.routeKey());
                }
            }
            long defaultCount = nodeEdges.stream().filter(OrchestrationEdgeDto::defaultEdge).count();
            if (defaultCount > 1) {
                throw new IllegalArgumentException("node has multiple default edges: " + node.nodeKey());
            }
            if (nodeEdges.size() > 1 && defaultCount == 0) {
                throw new IllegalArgumentException("branching node requires a default edge: " + node.nodeKey());
            }
        }

        Set<String> visited = new HashSet<>();
        OrchestrationNodeDto startNode = nodes.stream().filter(node -> node.nodeType() == OrchestrationNodeType.START).findFirst().orElseThrow();
        traverse(startNode.nodeKey(), outgoing, visited);
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException("orchestration contains unreachable nodes");
        }
    }

    private void traverse(String nodeKey, Map<String, List<OrchestrationEdgeDto>> outgoing, Set<String> visited) {
        if (!visited.add(nodeKey)) {
            return;
        }
        for (OrchestrationEdgeDto edge : outgoing.getOrDefault(nodeKey, List.of())) {
            traverse(edge.targetNodeKey(), outgoing, visited);
        }
    }

    private AssistantReleaseDto createAssistantRelease(String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        captureEffectiveResource(snapshotMap, assistant.modelPolicy().providerResourceId(), "ASSISTANT_DEFAULT_MODEL");
        KnowledgeBindingSnapshotDto assistantKnowledge = resolveAssistantKnowledgeBinding(assistant);

        List<AssistantReleaseAgentDto> releaseAgents = new ArrayList<>();
        for (AgentDto agent : orderAgentsForAssistant(assistantId)) {
            captureEffectiveResource(snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());

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
                resolveAgentKnowledgeBinding(assistantKnowledge, agent),
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
            assistantKnowledge,
            List.copyOf(snapshotMap.values()),
            List.copyOf(releaseAgents),
            getOrCreateOrchestration(assistantId),
            assistant.modelPolicy(),
            assistant.ragPolicy(),
            assistant.memoryPolicy()
        );
        List<AssistantReleaseDto> releases = new ArrayList<>(assistantReleases.getOrDefault(assistantId, List.of()));
        releases.add(release);
        assistantReleases.put(assistantId, releases);
        return release;
    }

    private KnowledgeBindingSnapshotDto resolveAssistantKnowledgeBinding(AssistantDto assistant) {
        if (!assistant.ragPolicy().enabled() || assistant.ragPolicy().knowledgeBaseId() == null || assistant.ragPolicy().knowledgeBaseId().isBlank()) {
            return null;
        }
        return knowledgeService.resolveKnowledgeBinding(assistant.ragPolicy().knowledgeBaseId());
    }

    private KnowledgeBindingSnapshotDto resolveAgentKnowledgeBinding(KnowledgeBindingSnapshotDto assistantKnowledge, AgentDto agent) {
        if (!agent.executionPolicy().ragEnabled()) {
            return null;
        }
        if (agent.executionPolicy().inheritAssistantKnowledge()) {
            return null;
        }
        if (agent.executionPolicy().knowledgeBaseId() == null || agent.executionPolicy().knowledgeBaseId().isBlank()) {
            return assistantKnowledge;
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

    private String findResourceDeletionBlocker(String resourceId) {
        ResourceDto resource = toResourceView(findResource(resourceId));
        return listResourceReferences(resource).stream()
            .filter(ResourceReferenceDto::blocksDeletion)
            .map(this::toResourceDeletionMessage)
            .findFirst()
            .orElse(null);
    }

    private String findResourceVersionDeletionBlocker(String versionId) {
        // Release-frozen refs are the only ones that carry resourceVersionId, but they don't block deletion.
        // Active bindings block deletion but don't reference specific versions.
        // So we still need to scan for the edge case where a specific version is frozen in a release
        // that was marked as blocking (currently none are, but keep the logic correct).
        return resources.stream()
            .map(this::toResourceView)
            .flatMap(resource -> listResourceReferences(resource).stream())
            .filter(ResourceReferenceDto::blocksDeletion)
            .filter(reference -> versionId.equals(reference.resourceVersionId()))
            .map(this::toResourceVersionDeletionMessage)
            .findFirst()
            .orElse(null);
    }

    private List<ResourceReferenceDto> listResourceReferences(ResourceDto resource) {
        List<ResourceReferenceDto> references = new ArrayList<>();

        for (CatalogRepository.ResourceBindingRef ref : repository.findResourceBindings(resource.id())) {
            String sourceName = resolveSourceName(ref.sourceType(), ref.sourceId());
            references.add(toResourceReference(resource, ref.bindingKind(), ref.sourceType(), ref.sourceId(), sourceName, null, null, true));
        }

        for (CatalogRepository.ReleaseResourceRef ref : repository.findReleaseResourceRefs(resource.id())) {
            String assistantName = resolveSourceName("ASSISTANT", ref.assistantId());
            String releaseVersion = findReleaseVersion(ref.assistantId(), ref.releaseId());
            references.add(toResourceReference(
                resource,
                "RELEASE_FROZEN",
                "ASSISTANT_RELEASE",
                ref.releaseId(),
                assistantName + "@" + releaseVersion,
                ref.resourceVersionId(),
                ref.resourceVersion(),
                false
            ));
        }

        references.sort(Comparator
            .comparing(ResourceReferenceDto::referenceKind)
            .thenComparing(ResourceReferenceDto::sourceName)
            .thenComparing(reference -> reference.resourceVersionId() == null ? "" : reference.resourceVersionId()));
        return references;
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

    private String findReleaseVersion(String assistantId, String releaseId) {
        List<AssistantReleaseDto> releases = assistantReleases.get(assistantId);
        if (releases == null) return releaseId;
        return releases.stream()
            .filter(r -> r.id().equals(releaseId)).findFirst()
            .map(AssistantReleaseDto::releaseVersion).orElse(releaseId);
    }

    private ResourceReferenceDto toResourceReference(
        ResourceDto resource,
        String referenceKind,
        String sourceType,
        String sourceId,
        String sourceName,
        String resourceVersionId,
        String resourceVersion,
        boolean blocksDeletion
    ) {
        return new ResourceReferenceDto(
            resource.id(),
            resource.name(),
            resource.type(),
            resource.shareScope(),
            resource.ownerType() + ":" + resource.ownerId(),
            resource.latestVersion() == null ? null : resource.latestVersion().version(),
            resource.effectiveVersion() == null ? null : resource.effectiveVersion().version(),
            referenceKind,
            sourceType,
            sourceId,
            sourceName,
            resourceVersionId,
            resourceVersion,
            blocksDeletion
        );
    }

    private String toResourceDeletionMessage(ResourceReferenceDto reference) {
        return switch (reference.referenceKind()) {
            case "ASSISTANT_DEFAULT_MODEL" -> "resource is used as assistant default model: " + reference.sourceName();
            case "AGENT_OVERRIDE_MODEL" -> "resource is used as agent override model: " + reference.sourceName();
            case "AGENT_SKILL_ENABLED" -> "resource is used as agent skill: " + reference.sourceName();
            case "AGENT_TOOL_ENABLED" -> "resource is used as agent tool: " + reference.sourceName();
            default -> "resource is still referenced: " + reference.sourceName();
        };
    }

    private String toResourceVersionDeletionMessage(ResourceReferenceDto reference) {
        return "resource version is still referenced: " + reference.sourceName();
    }

    private OrchestrationNodeDto toNode(AgentDto agent) {
        return new OrchestrationNodeDto(
            "node-" + agent.id(),
            agent.name(),
            OrchestrationNodeType.AGENT,
            agent.responsibility(),
            agent.id(),
            null
        );
    }

    private List<OrchestrationEdgeDto> buildSequentialEdges(List<OrchestrationNodeDto> nodes) {
        List<OrchestrationEdgeDto> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            OrchestrationNodeDto current = nodes.get(i);
            OrchestrationNodeDto next = nodes.get(i + 1);
            edges.add(new OrchestrationEdgeDto(
                "edge-" + current.nodeKey() + "-" + next.nodeKey(),
                current.nodeKey(),
                next.nodeKey(),
                "default",
                current.nodeType() == OrchestrationNodeType.START ? "开始处理" : "默认流转",
                true
            ));
        }
        return edges;
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
                assistant.currentRelease(),
                assistant.releases(),
                normalizeAssistantModelPolicy(assistant.modelPolicy()),
                normalizeRagPolicy(assistant.ragPolicy()),
                normalizeMemoryPolicy(assistant.memoryPolicy())
            ))
            .toList());
        resources.clear();
        resources.addAll(snapshot.resources());
        agents.clear();
        agents.addAll(snapshot.agents().stream().map(this::normalizeLoadedAgent).toList());
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
                    normalizeKnowledgeBindingSnapshot(release.assistantKnowledge()),
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
                            normalizeKnowledgeBindingSnapshot(agent.knowledge()),
                            agent.skillResourceVersionIds(),
                            agent.toolResourceVersionIds()
                        ))
                        .toList(),
                    release.orchestration(),
                    normalizeAssistantModelPolicy(release.modelPolicy()),
                    normalizeRagPolicy(release.ragPolicy()),
                    normalizeMemoryPolicy(release.memoryPolicy())
                ))
                .toList()
        ));
        orchestrations.clear();
        orchestrations.putAll(snapshot.orchestrations());
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
                normalizedPolicy.systemPrompt(),
                normalizedPolicy.ragEnabled(),
                normalizedPolicy.inheritAssistantKnowledge(),
                normalizedPolicy.knowledgeBaseId(),
                normalizedPolicy.memoryWindowSize(),
                List.copyOf(enabledSkillResourceIds),
                List.copyOf(enabledToolResourceIds)
            )
        );
    }

    private void persistState() {
        repository.save(new CatalogRepository.CatalogSnapshot(
            List.copyOf(domains),
            List.copyOf(scenarios),
            List.copyOf(assistants),
            List.copyOf(agents),
            List.copyOf(resources),
            Map.copyOf(resourceVersions),
            Map.copyOf(assistantReleases),
            Map.copyOf(orchestrations)
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
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION", "compatible-lab"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_PROJECT", "default-project"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_REGION", "local"),
                    0.2,
                    1200
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
            return new AssistantModelPolicyDto(resolveDefaultResourceId(ResourceType.LLM_MODEL, null));
        }
        return new AssistantModelPolicyDto(policy.providerResourceId());
    }

    private RagPolicyDto normalizeRagPolicy(RagPolicyDto policy) {
        if (policy == null) {
            String defaultKnowledgeBaseId = resolveDefaultKnowledgeBaseId(null);
            return new RagPolicyDto(defaultKnowledgeBaseId != null, defaultKnowledgeBaseId);
        }
        return new RagPolicyDto(policy.enabled(), normalizeOptionalText(policy.knowledgeBaseId()));
    }

    private MemoryPolicyDto normalizeMemoryPolicy(MemoryPolicyDto policy) {
        if (policy == null) {
            return new MemoryPolicyDto(true, 8);
        }
        return new MemoryPolicyDto(policy.enabled(), policy.windowSize());
    }

    private AgentExecutionPolicyDto normalizeAgentExecutionPolicy(AgentExecutionPolicyDto policy) {
        if (policy == null) {
            String defaultKnowledgeBaseId = resolveDefaultKnowledgeBaseId(null);
            return new AgentExecutionPolicyDto(true, null, "", defaultKnowledgeBaseId != null, true, defaultKnowledgeBaseId, 8, List.of(), List.of());
        }
        return new AgentExecutionPolicyDto(
            policy.inheritAssistantDefaults(),
            policy.modelResourceId(),
            normalizeOptionalText(policy.systemPrompt()),
            policy.ragEnabled(),
            policy.inheritAssistantKnowledge(),
            normalizeOptionalText(policy.knowledgeBaseId()),
            policy.memoryWindowSize(),
            policy.skillResourceIds() == null ? List.of() : List.copyOf(policy.skillResourceIds()),
            policy.toolResourceIds() == null ? List.of() : List.copyOf(policy.toolResourceIds())
        );
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

    private String resolveDefaultResourceId(ResourceType type, String preferredResourceId) {
        if (preferredResourceId != null && resources.stream().anyMatch(resource -> resource.id().equals(preferredResourceId))) {
            return preferredResourceId;
        }
        return resources.stream()
            .filter(resource -> resource.type() == type)
            .map(ResourceDto::id)
            .findFirst()
            .orElse(null);
    }

    private String resolveDefaultKnowledgeBaseId(String preferredKnowledgeBaseId) {
        return knowledgeService.resolveDefaultKnowledgeBaseId(preferredKnowledgeBaseId);
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
