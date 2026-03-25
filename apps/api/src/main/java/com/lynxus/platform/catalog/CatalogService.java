package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.OrchestrationNodeType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private final CatalogRepository repository;
    private boolean initialized;
    private final List<BusinessDomainDto> domains = new ArrayList<>();
    private final List<ScenarioDto> scenarios = new ArrayList<>();
    private final List<AssistantDto> assistants = new ArrayList<>();
    private final List<AgentDto> agents = new ArrayList<>();
    private final List<ResourceDto> resources = new ArrayList<>();
    private final Map<String, List<ResourceVersionDto>> resourceVersions = new LinkedHashMap<>();
    private final Map<String, List<AssistantReleaseDto>> assistantReleases = new LinkedHashMap<>();
    private final Map<String, AssistantOrchestrationDto> orchestrations = new LinkedHashMap<>();

    public CatalogService() {
        this(new InMemoryCatalogRepository(), true);
    }

    @Autowired
    public CatalogService(CatalogRepository repository) {
        this(repository, false);
    }

    CatalogService(CatalogRepository repository, boolean seedIfEmpty) {
        this.repository = repository;
        if (seedIfEmpty) {
            ensureLoaded();
            initializeDemoDataIfEmpty();
        }
    }

    public CatalogSummaryDto summary() {
        ensureLoaded();
        return new CatalogSummaryDto(
            listDomains(),
            listScenarios(),
            listAssistants(),
            listAgents(),
            listResources(),
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
            existing.resources()
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
            request.instructions(),
            List.of(),
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
            request.instructions(),
            existing.toolVersionPins(),
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

    public AgentDto updateAgentToolVersionPins(String agentId, UpdateAgentToolVersionPinsRequest request) {
        ensureLoaded();
        AgentDto agent = findAgent(agentId);
        Map<String, ToolVersionPinDto> existingToolVersionPins = agent.toolVersionPins().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.resourceVersionId(), item), Map::putAll);

        List<ToolVersionPinDto> updatedToolVersionPins = request.toolVersionPins().stream()
            .map(toolVersionPin -> toToolVersionPin(agentId, existingToolVersionPins, toolVersionPin))
            .toList();

        AgentDto updated = new AgentDto(
            agent.id(),
            agent.assistantId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            updatedToolVersionPins,
            agent.executionPolicy()
        );
        replace(agents, AgentDto::id, updated);
        persistState();
        return updated;
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
                ? new CreateResourceVersionRequest("初始版本", "digest-" + resourceId, VersionStatus.DRAFT, defaultConfiguration(resource.type()))
                : new CreateResourceVersionRequest(
                    request.initialVersion().summary(),
                    request.initialVersion().configDigest(),
                    request.initialVersion().status(),
                    normalizeConfiguration(resource.type(), request.initialVersion().configuration())
                )
        );
        return toResourceView(resource);
    }

    public List<ResourceVersionDto> listResourceVersions(String resourceId) {
        ensureLoaded();
        findResource(resourceId);
        return versionsFor(resourceId);
    }

    public ResourceVersionDto createResourceVersion(String resourceId, CreateResourceVersionRequest request) {
        ensureLoaded();
        ResourceDto resource = findResource(resourceId);
        VersionStatus status = request.status() == null ? VersionStatus.DRAFT : request.status();
        List<ResourceVersionDto> existingVersions = versionsFor(resourceId).stream()
            .map(version -> status == VersionStatus.PUBLISHED && version.status() == VersionStatus.PUBLISHED
                ? new ResourceVersionDto(
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
        ResourceVersionDto created = new ResourceVersionDto(
            nextId("resource-version"),
            resource.id(),
            nextResourceVersion(existingVersions),
            status,
            request.summary(),
            request.configDigest() == null || request.configDigest().isBlank() ? "digest-" + nextId("cfg") : request.configDigest(),
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null,
            normalizeConfiguration(resource.type(), request.configuration())
        );
        existingVersions.add(created);
        resourceVersions.put(resourceId, existingVersions);
        persistState();
        return created;
    }

    public ResourceVersionDto publishResourceVersion(String resourceId, String versionId) {
        ensureLoaded();
        findResource(resourceId);
        List<ResourceVersionDto> updatedVersions = versionsFor(resourceId).stream()
            .map(version -> new ResourceVersionDto(
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
        return updatedVersions.stream().filter(item -> item.id().equals(versionId)).findFirst().orElseThrow();
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

        List<ResourceVersionDto> updatedVersions = versionsFor(resourceId).stream()
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
                ResourceType.KNOWLEDGE_BASE,
                "知识库",
                "承载文档语料、检索策略和索引同步配置，供助手在问答与决策阶段检索知识。",
                List.of("数据来源", "同步方式", "检索模式", "Embedding 模型", "切片策略", "默认召回数", "文档规模"),
                defaultConfiguration(ResourceType.KNOWLEDGE_BASE)
            ),
            new ResourceBlueprintDto(
                ResourceType.SKILL,
                "Skill",
                "承载可执行能力的调用协议、输入输出契约和超时重试策略，适合封装业务动作。",
                List.of("运行方式", "调用端点", "鉴权方式", "超时设置", "重试策略", "输入 Schema", "输出 Schema"),
                defaultConfiguration(ResourceType.SKILL)
            ),
            new ResourceBlueprintDto(
                ResourceType.MCP,
                "MCP",
                "承载外部工具服务的连接方式、命名空间和暴露工具清单，适合接入系统能力。",
                List.of("服务名称", "传输协议", "连接地址", "命名空间", "鉴权方式", "心跳设置", "暴露工具"),
                defaultConfiguration(ResourceType.MCP)
            ),
            new ResourceBlueprintDto(
                ResourceType.LLM_MODEL,
                "LLM 模型",
                "承载多供应商模型连接、默认参数和鉴权入口，用于助手和智能体的真实模型调用。",
                List.of("供应商类型", "模型 ID", "Base URL", "API Key 环境变量", "组织/项目/区域", "Temperature", "Max Tokens"),
                defaultConfiguration(ResourceType.LLM_MODEL)
            ),
            new ResourceBlueprintDto(
                ResourceType.PROMPT_TEMPLATE,
                "Prompt 模板",
                "承载系统提示、用户模板和结构化输出约束，供助手和智能体复用。",
                List.of("模板类型", "System Prompt", "User Prompt Template", "响应格式"),
                defaultConfiguration(ResourceType.PROMPT_TEMPLATE)
            )
        );
    }

    public ToolVersionPinDto pinToolVersion(PinToolVersionRequest request) {
        ensureLoaded();
        AgentDto agent = findAgent(request.consumerId());
        ResourceDto resource = findResource(request.resourceId());
        validateToolVersionPin(agent, resource, effectiveVersion(resource).id());
        ResourceVersionDto version = effectiveVersion(resource);
        ToolVersionPinDto toolVersionPin = new ToolVersionPinDto(
            nextId("tool-version-pin"),
            request.resourceId(),
            version.id(),
            version.version(),
            request.consumerType(),
            request.consumerId(),
            Instant.now()
        );
        AgentDto updated = new AgentDto(
            agent.id(),
            agent.assistantId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            append(agent.toolVersionPins(), toolVersionPin),
            agent.executionPolicy()
        );
        replace(agents, AgentDto::id, updated);
        persistState();
        return toolVersionPin;
    }

    public synchronized boolean initializeDemoDataIfEmpty() {
        ensureLoaded();
        if (!isCatalogEmpty()) {
            return false;
        }
        seed();
        persistState();
        return true;
    }

    private synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        restore(repository.load());
        initialized = true;
    }

    private void seed() {
        BusinessDomainDto domain = new BusinessDomainDto(
            "domain-support",
            "智能客服域",
            "用于多智能体客服编排的演示业务域",
            List.of(),
            List.of()
        );
        domains.add(domain);

        ScenarioDto scenario = new ScenarioDto(
            "scenario-customer-ops",
            domain.id(),
            "智能客服协同处理",
            "在单助手内完成 FAQ、售后策略和人工协同闭环",
            new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of()
        );
        scenarios.add(scenario);

        String defaultLlmResourceId = defaultLlmResourceId();
        AssistantDto assistant = new AssistantDto(
            "assistant-customer-ops",
            scenario.id(),
            "客服协同助手",
            "负责问题分诊、知识回答、售后策略和人工协同闭环。",
            new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of(),
            null,
            List.of(),
            new AssistantModelPolicyDto(defaultLlmResourceId, "resource-prompt-router"),
            new RagPolicyDto(true, "resource-kb-support"),
            new MemoryPolicyDto(true, 10)
        );
        assistants.add(assistant);

        ResourceDto kb = new ResourceDto(
            "resource-kb-support",
            domain.id(),
            "客服知识库",
            ResourceType.KNOWLEDGE_BASE,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "包含 FAQ、售后规则和人工协同说明的演示知识库",
            "客服知识运营",
            List.of("FAQ", "售后", "协同"),
            null,
            null,
            List.of()
        );
        ResourceDto llmModel = new ResourceDto(
            "resource-llm-openai",
            domain.id(),
            "OpenAI 主模型",
            ResourceType.LLM_MODEL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "多智能体执行默认模型",
            "平台 AI 团队",
            List.of("LLM", "OpenAI"),
            null,
            null,
            List.of()
        );
        ResourceDto compatibleLlmModel = new ResourceDto(
            "resource-llm-compatible",
            domain.id(),
            "自定义兼容模型",
            ResourceType.LLM_MODEL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "支持 OpenAI Compatible 网关",
            "平台 AI 团队",
            List.of("LLM", "兼容网关"),
            null,
            null,
            List.of()
        );
        ResourceDto routerPrompt = new ResourceDto(
            "resource-prompt-router",
            domain.id(),
            "路由 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于问题分诊和路由决策",
            "客服协同助手团队",
            List.of("Prompt", "Router"),
            null,
            null,
            List.of()
        );
        ResourceDto faqPrompt = new ResourceDto(
            "resource-prompt-faq",
            domain.id(),
            "FAQ Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于知识问答回复",
            "客服协同助手团队",
            List.of("Prompt", "FAQ"),
            null,
            null,
            List.of()
        );
        ResourceDto policyPrompt = new ResourceDto(
            "resource-prompt-policy",
            domain.id(),
            "售后策略 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于售后策略判定",
            "客服协同助手团队",
            List.of("Prompt", "售后"),
            null,
            null,
            List.of()
        );
        ResourceDto handoffPrompt = new ResourceDto(
            "resource-prompt-handoff",
            domain.id(),
            "人工协同 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于人工交接后的总结与闭环",
            "客服协同助手团队",
            List.of("Prompt", "人工协同"),
            null,
            null,
            List.of()
        );
        ResourceDto refundSkill = new ResourceDto(
            "resource-skill-refund",
            domain.id(),
            "售后策略 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "通过 HTTP 协议返回退款与补偿策略",
            "售后策略团队",
            List.of("Skill", "退款"),
            null,
            null,
            List.of()
        );
        ResourceDto ticketMcp = new ResourceDto(
            "resource-mcp-ticket",
            domain.id(),
            "工单协同 MCP",
            ResourceType.MCP,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于创建和同步人工协同工单",
            "客服平台集成",
            List.of("MCP", "工单"),
            null,
            null,
            List.of()
        );
        resources.addAll(List.of(kb, llmModel, compatibleLlmModel, routerPrompt, faqPrompt, policyPrompt, handoffPrompt, refundSkill, ticketMcp));

        ResourceVersionDto kbPublished = seedResourceVersion(
            kb.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "客服知识库演示版",
            "digest-kb-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.KNOWLEDGE_BASE,
                new KnowledgeBaseConfigDto("SEED_DATA", "seed://support-faq", "MANUAL", "HYBRID", "text-embedding-3-large", "markdown-512-overlap-80", 5, 12),
                null,
                null,
                null,
                null
            )
        );
        seedResourceVersion(
            llmModel.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "OpenAI 模型基线版",
            "digest-llm-openai-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.LLM_MODEL,
                null,
                null,
                null,
                new LlmModelConfigDto("OPENAI", "gpt-4.1-mini", "https://api.openai.com/v1", "OPENAI_API_KEY", "lynxus-demo", "customer-ops", "global", 0.2, 1200),
                null
            )
        );
        seedResourceVersion(
            compatibleLlmModel.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "兼容网关模型基线版",
            "digest-llm-compatible-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.LLM_MODEL,
                null,
                null,
                null,
                new LlmModelConfigDto(
                    "OPENAI_COMPATIBLE",
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID", "demo-compatible-model"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_BASE_URL", "http://localhost:11434/v1"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR", "OPENAI_COMPATIBLE_API_KEY"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION", "compatible-lab"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_PROJECT", "customer-ops"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_REGION", "local"),
                    0.2,
                    1200
                ),
                null
            )
        );
        seedResourceVersion(
            routerPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "路由 Prompt",
            "digest-prompt-router-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto(
                    "STRUCTURED_OUTPUT",
                    "你是客服协同编排里的路由智能体，请判断问题应该进入 FAQ、售后策略还是人工协同。",
                    "用户问题：{{question}}\n知识上下文：{{knowledge_context}}\n请输出路由决策。",
                    "json"
                )
            )
        );
        seedResourceVersion(
            faqPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "FAQ Prompt",
            "digest-prompt-faq-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto(
                    "CHAT",
                    "你是 FAQ 回答智能体，请结合知识检索结果输出简洁、准确的回复。",
                    "用户问题：{{question}}\n知识上下文：{{knowledge_context}}",
                    "markdown"
                )
            )
        );
        seedResourceVersion(
            policyPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "售后 Prompt",
            "digest-prompt-policy-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto(
                    "STRUCTURED_OUTPUT",
                    "你是售后策略智能体，请结合知识和工具结果给出结构化判断。",
                    "用户问题：{{question}}\n知识上下文：{{knowledge_context}}\n工具结果：{{tool_results}}",
                    "json"
                )
            )
        );
        seedResourceVersion(
            handoffPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "人工协同 Prompt",
            "digest-prompt-handoff-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto(
                    "CHAT",
                    "你是人工协同闭环智能体，请根据人工动作补充后续说明和最终回复。",
                    "用户问题：{{question}}\n人工处理说明：{{human_input}}\n工具结果：{{tool_results}}",
                    "markdown"
                )
            )
        );
        ResourceVersionDto refundSkillVersion = seedResourceVersion(
            refundSkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "售后策略 Skill",
            "digest-skill-refund-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                new SkillConfigDto("HTTP", "http://demo.local/skills/refund-policy", "POST", "SERVICE_ACCOUNT", 15, "NONE", "{question}", "{eligibility, routeKey, actionPlan}"),
                null,
                null,
                null
            )
        );
        ResourceVersionDto ticketMcpVersion = seedResourceVersion(
            ticketMcp.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "工单协同 MCP",
            "digest-mcp-ticket-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.MCP,
                null,
                null,
                new McpConfigDto("ticketing-server", "STREAMABLE_HTTP", "http://demo.local/mcp/ticketing", "support.ticket", "NONE", 30, List.of("create_ticket", "append_comment")),
                null,
                null
            )
        );

        agents.add(new AgentDto(
            "agent-router",
            assistant.id(),
            "问题分诊智能体",
            "router",
            "识别问题类型，决定 FAQ、售后策略或人工协同分支。",
            List.of(new ToolVersionPinDto("tool-version-pin-router-kb", kb.id(), kbPublished.id(), kbPublished.version(), "AGENT", "agent-router", Instant.now())),
            new AgentExecutionPolicyDto(true, null, routerPrompt.id(), "输出 route_key 和摘要。", true, kb.id(), 8, List.of())
        ));
        agents.add(new AgentDto(
            "agent-faq",
            assistant.id(),
            "FAQ 回答智能体",
            "faq",
            "基于知识检索结果输出最终 FAQ 回复。",
            List.of(),
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, faqPrompt.id(), "回答简单 FAQ 并完成会话。", true, kb.id(), 8, List.of())
        ));
        agents.add(new AgentDto(
            "agent-policy",
            assistant.id(),
            "售后策略智能体",
            "policy",
            "调用售后策略 Skill，给出退款或补偿结论。",
            List.of(new ToolVersionPinDto("tool-version-pin-policy-skill", refundSkill.id(), refundSkillVersion.id(), refundSkillVersion.version(), "AGENT", "agent-policy", Instant.now())),
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, policyPrompt.id(), "结合工具输出结构化 route_key。", true, kb.id(), 8, List.of(refundSkill.id()))
        ));
        agents.add(new AgentDto(
            "agent-coordinator",
            assistant.id(),
            "人工协同闭环智能体",
            "handoff",
            "在人工处理后整理摘要、调用工单 MCP，并生成闭环答复。",
            List.of(new ToolVersionPinDto("tool-version-pin-handoff-mcp", ticketMcp.id(), ticketMcpVersion.id(), ticketMcpVersion.version(), "AGENT", "agent-coordinator", Instant.now())),
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, handoffPrompt.id(), "根据人工动作补充最终回复。", false, null, 12, List.of(ticketMcp.id()))
        ));

        orchestrations.put(assistant.id(), new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            "GRAPH",
            List.of(
                new OrchestrationNodeDto("start", "开始", OrchestrationNodeType.START, "接收用户消息。", null, null),
                new OrchestrationNodeDto("route", "问题分诊", OrchestrationNodeType.AGENT, "判断路由分支。", "agent-router", null),
                new OrchestrationNodeDto("faq", "FAQ 回答", OrchestrationNodeType.AGENT, "处理常规 FAQ。", "agent-faq", null),
                new OrchestrationNodeDto("policy", "售后策略", OrchestrationNodeType.AGENT, "处理退款与补偿策略。", "agent-policy", null),
                new OrchestrationNodeDto(
                    "human-review",
                    "人工介入",
                    OrchestrationNodeType.HUMAN,
                    "等待人工确认或补充处理意见。",
                    null,
                    new HumanNodeConfigDto("人工介入待办", "请确认是否接管，并补充处理说明。", "CONFIRM", "human-confirmed")
                ),
                new OrchestrationNodeDto("handoff-close", "闭环总结", OrchestrationNodeType.AGENT, "人工处理后生成闭环答复。", "agent-coordinator", null),
                new OrchestrationNodeDto("end", "结束", OrchestrationNodeType.END, "流程结束。", null, null)
            ),
            List.of(
                new OrchestrationEdgeDto("edge-start-route", "start", "route", "default", "开始处理", true),
                new OrchestrationEdgeDto("edge-route-faq", "route", "faq", "faq", "进入 FAQ 分支", false),
                new OrchestrationEdgeDto("edge-route-policy", "route", "policy", "after_sales", "进入售后分支", false),
                new OrchestrationEdgeDto("edge-route-human", "route", "human-review", "human_handoff", "直接人工介入", false),
                new OrchestrationEdgeDto("edge-route-fallback", "route", "faq", "default", "默认走 FAQ", true),
                new OrchestrationEdgeDto("edge-faq-end", "faq", "end", "default", "FAQ 结束", true),
                new OrchestrationEdgeDto("edge-policy-end", "policy", "end", "resolved", "售后自动完成", false),
                new OrchestrationEdgeDto("edge-policy-human", "policy", "human-review", "manual_review", "售后转人工", false),
                new OrchestrationEdgeDto("edge-policy-default", "policy", "end", "default", "默认完成", true),
                new OrchestrationEdgeDto("edge-human-handoff", "human-review", "handoff-close", "human-confirmed", "人工确认后闭环", true),
                new OrchestrationEdgeDto("edge-close-end", "handoff-close", "end", "default", "闭环完成", true)
            )
        ));

        createAssistantRelease(assistant.id(), "1.0.0", VersionStatus.PUBLISHED);
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
        return new BusinessDomainDto(domain.id(), domain.name(), domain.description(), domainScenarios, domainResources);
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
                    existing.description() == null || existing.description().isBlank() ? agent.instructions() : existing.description(),
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
            outgoing.computeIfAbsent(edge.sourceNodeKey(), __ -> new ArrayList<>()).add(edge);
        }

        for (OrchestrationNodeDto node : nodes) {
            List<OrchestrationEdgeDto> nodeEdges = outgoing.getOrDefault(node.nodeKey(), List.of());
            if (node.nodeType() != OrchestrationNodeType.END && nodeEdges.isEmpty()) {
                throw new IllegalArgumentException("node has no outgoing edges: " + node.nodeKey());
            }
            long defaultCount = nodeEdges.stream().filter(OrchestrationEdgeDto::defaultEdge).count();
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

    private ToolVersionPinDto toToolVersionPin(
        String agentId,
        Map<String, ToolVersionPinDto> existingToolVersionPins,
        ToolVersionPinTarget target
    ) {
        AgentDto agent = findAgent(agentId);
        ResourceDto resource = findResource(target.resourceId());
        validateToolVersionPin(agent, resource, target.resourceVersionId());
        ResourceVersionDto version = findResourceVersion(target.resourceId(), target.resourceVersionId());
        return existingToolVersionPins.getOrDefault(
            version.id(),
            new ToolVersionPinDto(nextId("tool-version-pin"), target.resourceId(), version.id(), version.version(), "AGENT", agentId, Instant.now())
        );
    }

    private AssistantReleaseDto createAssistantRelease(String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        captureEffectiveResource(snapshotMap, assistant.modelPolicy().providerResourceId(), "ASSISTANT_DEFAULT_MODEL");
        captureEffectiveResource(snapshotMap, assistant.modelPolicy().promptTemplateResourceId(), "ASSISTANT_DEFAULT_PROMPT");
        if (assistant.ragPolicy().enabled()) {
            captureEffectiveResource(snapshotMap, assistant.ragPolicy().knowledgeBaseResourceId(), "ASSISTANT_DEFAULT_RAG");
        }

        List<AssistantReleaseAgentDto> releaseAgents = new ArrayList<>();
        for (AgentDto agent : orderAgentsForAssistant(assistantId)) {
            captureEffectiveResource(snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());
            captureEffectiveResource(snapshotMap, agent.executionPolicy().promptTemplateResourceId(), agent.name());
            if (agent.executionPolicy().ragEnabled()) {
                captureEffectiveResource(snapshotMap, agent.executionPolicy().knowledgeBaseResourceId(), agent.name());
            }

            List<String> toolResourceVersionIds = new ArrayList<>();
            for (String toolResourceId : agent.executionPolicy().toolResourceIds()) {
                ToolVersionPinDto toolVersionPin = findRequiredToolVersionPin(agent, toolResourceId);
                toolResourceVersionIds.add(toolVersionPin.resourceVersionId());
                capturePinnedToolResource(snapshotMap, toolVersionPin, agent.name());
            }
            releaseAgents.add(new AssistantReleaseAgentDto(
                agent.id(),
                agent.name(),
                agent.role(),
                agent.instructions(),
                agent.executionPolicy(),
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

    private void captureEffectiveResource(Map<String, AssistantReleaseResourceDto> snapshotMap, String resourceId, String boundAgent) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = toResourceView(findResource(resourceId));
        ResourceVersionDto version = effectiveVersion(resource);
        mergeReleaseResource(snapshotMap, resource, version, boundAgent);
    }

    private void capturePinnedToolResource(
        Map<String, AssistantReleaseResourceDto> snapshotMap,
        ToolVersionPinDto toolVersionPin,
        String boundAgent
    ) {
        ResourceDto resource = toResourceView(findResource(toolVersionPin.resourceId()));
        ResourceVersionDto version = findResourceVersion(toolVersionPin.resourceId(), toolVersionPin.resourceVersionId());
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
        for (AssistantDto assistant : assistants) {
            if (resource.id().equals(assistant.modelPolicy().providerResourceId())) {
                references.add(toResourceReference(resource, "ASSISTANT_DEFAULT_MODEL", "ASSISTANT", assistant.id(), assistant.name(), null, null, true));
            }
            if (resource.id().equals(assistant.modelPolicy().promptTemplateResourceId())) {
                references.add(toResourceReference(resource, "ASSISTANT_DEFAULT_PROMPT", "ASSISTANT", assistant.id(), assistant.name(), null, null, true));
            }
            if (assistant.ragPolicy().enabled() && resource.id().equals(assistant.ragPolicy().knowledgeBaseResourceId())) {
                references.add(toResourceReference(resource, "ASSISTANT_DEFAULT_KNOWLEDGE_BASE", "ASSISTANT", assistant.id(), assistant.name(), null, null, true));
            }
        }

        for (AgentDto agent : agents) {
            if (resource.id().equals(agent.executionPolicy().modelResourceId())) {
                references.add(toResourceReference(resource, "AGENT_OVERRIDE_MODEL", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (resource.id().equals(agent.executionPolicy().promptTemplateResourceId())) {
                references.add(toResourceReference(resource, "AGENT_OVERRIDE_PROMPT", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (agent.executionPolicy().ragEnabled() && resource.id().equals(agent.executionPolicy().knowledgeBaseResourceId())) {
                references.add(toResourceReference(resource, "AGENT_OVERRIDE_KNOWLEDGE_BASE", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (agent.executionPolicy().toolResourceIds().contains(resource.id())) {
                references.add(toResourceReference(resource, "AGENT_TOOL_ENABLED", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            for (ToolVersionPinDto toolVersionPin : agent.toolVersionPins()) {
                if (resource.id().equals(toolVersionPin.resourceId())) {
                    references.add(toResourceReference(
                        resource,
                        "AGENT_TOOL_VERSION_PIN",
                        "AGENT",
                        agent.id(),
                        agent.name(),
                        toolVersionPin.resourceVersionId(),
                        toolVersionPin.resourceVersion(),
                        true
                    ));
                }
            }
        }

        for (Map.Entry<String, List<AssistantReleaseDto>> entry : assistantReleases.entrySet()) {
            AssistantDto assistant = assistants.stream()
                .filter(item -> item.id().equals(entry.getKey()))
                .findFirst()
                .orElse(null);
            String assistantName = assistant == null ? entry.getKey() : assistant.name();
            for (AssistantReleaseDto release : entry.getValue()) {
                for (AssistantReleaseResourceDto releaseResource : release.resources()) {
                    if (resource.id().equals(releaseResource.resourceId())) {
                        references.add(toResourceReference(
                            resource,
                            "RELEASE_FROZEN",
                            "ASSISTANT_RELEASE",
                            release.id(),
                            assistantName + "@" + release.releaseVersion(),
                            releaseResource.resourceVersionId(),
                            releaseResource.resourceVersion(),
                            false
                        ));
                    }
                }
            }
        }

        references.sort(Comparator
            .comparing(ResourceReferenceDto::referenceKind)
            .thenComparing(ResourceReferenceDto::sourceName)
            .thenComparing(reference -> reference.resourceVersionId() == null ? "" : reference.resourceVersionId()));
        return references;
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
            case "ASSISTANT_DEFAULT_PROMPT" -> "resource is used as assistant default prompt: " + reference.sourceName();
            case "ASSISTANT_DEFAULT_KNOWLEDGE_BASE" -> "resource is used as assistant default knowledge base: " + reference.sourceName();
            case "AGENT_OVERRIDE_MODEL" -> "resource is used as agent override model: " + reference.sourceName();
            case "AGENT_OVERRIDE_PROMPT" -> "resource is used as agent override prompt: " + reference.sourceName();
            case "AGENT_OVERRIDE_KNOWLEDGE_BASE" -> "resource is used as agent override knowledge base: " + reference.sourceName();
            case "AGENT_TOOL_ENABLED" -> "resource is used as agent tool: " + reference.sourceName();
            case "AGENT_TOOL_VERSION_PIN" -> "resource still has tool version pins on agent: " + reference.sourceName();
            default -> "resource is still referenced: " + reference.sourceName();
        };
    }

    private String toResourceVersionDeletionMessage(ResourceReferenceDto reference) {
        return switch (reference.referenceKind()) {
            case "AGENT_TOOL_VERSION_PIN" -> "resource version still pinned on agent tool: " + reference.sourceName();
            default -> "resource version is still referenced: " + reference.sourceName();
        };
    }

    private OrchestrationNodeDto toNode(AgentDto agent) {
        return new OrchestrationNodeDto(
            "node-" + agent.id(),
            agent.name(),
            OrchestrationNodeType.AGENT,
            agent.executionPolicy().inlinePrompt() == null || agent.executionPolicy().inlinePrompt().isBlank()
                ? agent.instructions()
                : agent.executionPolicy().inlinePrompt(),
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

    private boolean isCatalogEmpty() {
        return domains.isEmpty()
            && scenarios.isEmpty()
            && assistants.isEmpty()
            && agents.isEmpty()
            && resources.isEmpty()
            && resourceVersions.isEmpty()
            && assistantReleases.isEmpty()
            && orchestrations.isEmpty();
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
        resourceVersions.putAll(snapshot.resourceVersions());
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
                    release.resources(),
                    release.agents(),
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
        Set<String> enabledToolResourceIds = new HashSet<>(normalizedPolicy.toolResourceIds());
        List<ToolVersionPinDto> validToolPins = new ArrayList<>();
        for (ToolVersionPinDto toolVersionPin : agent.toolVersionPins()) {
            ResourceDto resource = resources.stream()
                .filter(item -> item.id().equals(toolVersionPin.resourceId()))
                .findFirst()
                .orElse(null);
            if (resource == null) {
                log.warn("Dropping legacy tool pin for missing resource agent={} resource={}", agent.id(), toolVersionPin.resourceId());
                continue;
            }
            if (resource.type() != ResourceType.SKILL && resource.type() != ResourceType.MCP) {
                log.warn("Dropping invalid non-tool version pin agent={} resource={} type={}", agent.id(), toolVersionPin.resourceId(), resource.type());
                continue;
            }
            validToolPins.add(toolVersionPin);
            enabledToolResourceIds.add(toolVersionPin.resourceId());
        }
        return new AgentDto(
            agent.id(),
            agent.assistantId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            validToolPins,
            new AgentExecutionPolicyDto(
                normalizedPolicy.inheritAssistantDefaults(),
                normalizedPolicy.modelResourceId(),
                normalizedPolicy.promptTemplateResourceId(),
                normalizedPolicy.inlinePrompt(),
                normalizedPolicy.ragEnabled(),
                normalizedPolicy.knowledgeBaseResourceId(),
                normalizedPolicy.memoryWindowSize(),
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
            .sorted(Comparator.comparing(ResourceVersionDto::createdAt))
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

    private ResourceVersionDto seedResourceVersion(
        String resourceId,
        String version,
        VersionStatus status,
        String summary,
        String configDigest,
        ResourceVersionConfigurationDto configuration
    ) {
        ResourceVersionDto created = new ResourceVersionDto(
            nextId("resource-version"),
            resourceId,
            version,
            status,
            summary,
            configDigest,
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null,
            configuration
        );
        List<ResourceVersionDto> versions = new ArrayList<>(resourceVersions.getOrDefault(resourceId, List.of()));
        versions.add(created);
        resourceVersions.put(resourceId, versions);
        return created;
    }

    private ResourceVersionConfigurationDto normalizeConfiguration(ResourceType type, ResourceVersionConfigurationDto configuration) {
        if (configuration == null) {
            return defaultConfiguration(type);
        }
        return switch (type) {
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(type, configuration.knowledgeBase() == null ? defaultConfiguration(type).knowledgeBase() : configuration.knowledgeBase(), null, null, null, null);
            case SKILL -> new ResourceVersionConfigurationDto(type, null, configuration.skill() == null ? defaultConfiguration(type).skill() : configuration.skill(), null, null, null);
            case MCP -> new ResourceVersionConfigurationDto(type, null, null, configuration.mcp() == null ? defaultConfiguration(type).mcp() : configuration.mcp(), null, null);
            case LLM_MODEL -> new ResourceVersionConfigurationDto(type, null, null, null, configuration.llmModel() == null ? defaultConfiguration(type).llmModel() : configuration.llmModel(), null);
            case PROMPT_TEMPLATE -> new ResourceVersionConfigurationDto(type, null, null, null, null, configuration.promptTemplate() == null ? defaultConfiguration(type).promptTemplate() : configuration.promptTemplate());
        };
    }

    private ResourceVersionConfigurationDto defaultConfiguration(ResourceType type) {
        return switch (type) {
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(
                type,
                new KnowledgeBaseConfigDto("SEED_DATA", "seed://default", "MANUAL", "HYBRID", "text-embedding-3-large", "markdown-512-overlap-80", 5, 0),
                null,
                null,
                null,
                null
            );
            case SKILL -> new ResourceVersionConfigurationDto(
                type,
                null,
                new SkillConfigDto("HTTP", "http://demo.local/skills/new-skill", "POST", "SERVICE_ACCOUNT", 15, "NONE", "{input}", "{output}"),
                null,
                null,
                null
            );
            case MCP -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                new McpConfigDto("demo-mcp-server", "STREAMABLE_HTTP", "http://demo.local/mcp/default", "default.namespace", "NONE", 30, List.of("tool_a")),
                null,
                null
            );
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
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
            case PROMPT_TEMPLATE -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto("CHAT", "你是执行智能体。", "用户问题：{{question}}\n知识上下文：{{knowledge_context}}", "markdown")
            );
        };
    }

    private AssistantModelPolicyDto normalizeAssistantModelPolicy(AssistantModelPolicyDto policy) {
        if (policy == null) {
            return new AssistantModelPolicyDto(
                resolveDefaultResourceId(ResourceType.LLM_MODEL, defaultLlmResourceId()),
                resolveDefaultResourceId(ResourceType.PROMPT_TEMPLATE, "resource-prompt-router")
            );
        }
        return new AssistantModelPolicyDto(policy.providerResourceId(), policy.promptTemplateResourceId());
    }

    private RagPolicyDto normalizeRagPolicy(RagPolicyDto policy) {
        if (policy == null) {
            String defaultKnowledgeBaseId = resolveDefaultResourceId(ResourceType.KNOWLEDGE_BASE, "resource-kb-support");
            return new RagPolicyDto(defaultKnowledgeBaseId != null, defaultKnowledgeBaseId);
        }
        return new RagPolicyDto(policy.enabled(), policy.knowledgeBaseResourceId());
    }

    private MemoryPolicyDto normalizeMemoryPolicy(MemoryPolicyDto policy) {
        if (policy == null) {
            return new MemoryPolicyDto(true, 8);
        }
        return new MemoryPolicyDto(policy.enabled(), policy.windowSize());
    }

    private AgentExecutionPolicyDto normalizeAgentExecutionPolicy(AgentExecutionPolicyDto policy) {
        if (policy == null) {
            String defaultKnowledgeBaseId = resolveDefaultResourceId(ResourceType.KNOWLEDGE_BASE, "resource-kb-support");
            return new AgentExecutionPolicyDto(true, null, null, "", defaultKnowledgeBaseId != null, defaultKnowledgeBaseId, 8, List.of());
        }
        return new AgentExecutionPolicyDto(
            policy.inheritAssistantDefaults(),
            policy.modelResourceId(),
            policy.promptTemplateResourceId(),
            policy.inlinePrompt(),
            policy.ragEnabled(),
            policy.knowledgeBaseResourceId(),
            policy.memoryWindowSize(),
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

    private ToolVersionPinDto findRequiredToolVersionPin(AgentDto agent, String toolResourceId) {
        ResourceDto resource = findResource(toolResourceId);
        List<ToolVersionPinDto> matches = agent.toolVersionPins().stream()
            .filter(toolVersionPin -> toolVersionPin.resourceId().equals(toolResourceId))
            .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("agent tool requires a pinned version before release: " + agent.name() + " -> " + resource.name());
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("agent tool must pin exactly one version: " + agent.name() + " -> " + resource.name());
        }
        validateToolVersionPin(agent, resource, matches.getFirst().resourceVersionId());
        return matches.getFirst();
    }

    private void validateToolVersionPin(AgentDto agent, ResourceDto resource, String resourceVersionId) {
        if (resource.type() != ResourceType.SKILL && resource.type() != ResourceType.MCP) {
            throw new IllegalArgumentException("tool version pins only support SKILL or MCP resources: " + resource.id());
        }
        if (!agent.executionPolicy().toolResourceIds().contains(resource.id())) {
            throw new IllegalArgumentException("tool version pin requires the resource to be enabled first: " + agent.name() + " -> " + resource.name());
        }
        findResourceVersion(resource.id(), resourceVersionId);
    }

    private String nextResourceVersion(List<ResourceVersionDto> versions) {
        if (versions.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = versions.getLast().version().split("\\.");
        int patch = Integer.parseInt(segments[2]) + 1;
        return segments[0] + "." + segments[1] + "." + patch;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<ToolVersionPinDto> append(List<ToolVersionPinDto> toolVersionPins, ToolVersionPinDto toolVersionPin) {
        List<ToolVersionPinDto> updated = new ArrayList<>(toolVersionPins);
        updated.add(toolVersionPin);
        return updated;
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

    private static String defaultLlmResourceId() {
        if (hasEnv("LYNXUS_OPENAI_COMPATIBLE_BASE_URL") || hasEnv("OPENAI_COMPATIBLE_API_KEY")) {
            return "resource-llm-compatible";
        }
        if (hasEnv("OPENAI_API_KEY")) {
            return "resource-llm-openai";
        }
        return "resource-llm-compatible";
    }

    private static boolean hasEnv(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
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
}
