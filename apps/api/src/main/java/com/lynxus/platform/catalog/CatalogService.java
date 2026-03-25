package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.OrchestrationNodeType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolProviderType;
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

@Service
public class CatalogService {
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
        ResourceVersionConfigurationDto normalizedConfiguration = normalizeConfiguration(resource.type(), request.configuration());
        if (status == VersionStatus.PUBLISHED) {
            validateVersionReadyForActivation(resource.type(), normalizedConfiguration);
        }
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
            normalizedConfiguration
        );
        existingVersions.add(created);
        resourceVersions.put(resourceId, existingVersions);
        persistState();
        return created;
    }

    public ResourceVersionDto publishResourceVersion(String resourceId, String versionId) {
        ensureLoaded();
        ResourceDto resource = findResource(resourceId);
        ResourceVersionDto targetVersion = findResourceVersion(resourceId, versionId);
        validateVersionReadyForActivation(resource.type(), targetVersion.configuration());
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
                "承载可发布的知识文档集合和默认召回数，供助手在问答与决策阶段检索知识。",
                List.of("默认召回数", "导入文档"),
                defaultConfiguration(ResourceType.KNOWLEDGE_BASE)
            ),
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
            new AssistantModelPolicyDto(defaultLlmResourceId),
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
        ResourceDto routerSkill = new ResourceDto(
            "resource-skill-router",
            domain.id(),
            "路由 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于问题分诊和路由决策的技能",
            "客服协同助手团队",
            List.of("Skill", "Router"),
            null,
            null,
            List.of()
        );
        ResourceDto faqSkill = new ResourceDto(
            "resource-skill-faq",
            domain.id(),
            "FAQ Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于知识问答回复的技能",
            "客服协同助手团队",
            List.of("Skill", "FAQ"),
            null,
            null,
            List.of()
        );
        ResourceDto policySkill = new ResourceDto(
            "resource-skill-policy",
            domain.id(),
            "售后策略 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于售后策略判定的技能",
            "客服协同助手团队",
            List.of("Skill", "售后"),
            null,
            null,
            List.of()
        );
        ResourceDto handoffSkill = new ResourceDto(
            "resource-skill-handoff",
            domain.id(),
            "人工协同 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "用于人工交接后的总结与闭环技能",
            "客服协同助手团队",
            List.of("Skill", "人工协同"),
            null,
            null,
            List.of()
        );
        ResourceDto refundTool = new ResourceDto(
            "resource-tool-refund",
            domain.id(),
            "售后策略 Tool",
            ResourceType.TOOL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            assistant.id(),
            "通过 HTTP provider 返回退款与补偿策略",
            "售后策略团队",
            List.of("Tool", "退款"),
            null,
            null,
            List.of()
        );
        ResourceDto ticketTool = new ResourceDto(
            "resource-tool-ticket",
            domain.id(),
            "工单协同 Tool",
            ResourceType.TOOL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "通过 MCP provider 创建和同步人工协同工单",
            "客服平台集成",
            List.of("Tool", "工单"),
            null,
            null,
            List.of()
        );
        resources.addAll(List.of(kb, llmModel, compatibleLlmModel, routerSkill, faqSkill, policySkill, handoffSkill, refundTool, ticketTool));

        ResourceVersionDto kbPublished = seedResourceVersion(
            kb.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "客服知识库演示版",
            "digest-kb-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.KNOWLEDGE_BASE,
                new KnowledgeBaseConfigDto(
                    5,
                    List.of(
                        new KnowledgeBaseDocumentDto("kb-doc-password", "密码重置流程", "密码重置可以通过登录页的忘记密码完成，若邮箱不可用则需要人工验证。", "manual://customer-support/password-reset"),
                        new KnowledgeBaseDocumentDto("kb-doc-refund-policy", "售后退款判定", "售后退款通常需要结合订单状态、支付时间和投诉原因综合判定。", "manual://customer-support/refund-policy"),
                        new KnowledgeBaseDocumentDto("kb-doc-escalation", "争议升级规则", "涉及争议、投诉或升级字样的请求应优先进入人工协同分支。", "manual://customer-support/escalation-rule"),
                        new KnowledgeBaseDocumentDto("kb-doc-ticketing", "人工协同工单规范", "人工协同时应创建工单，并保留问题摘要、处理意见与回访结果。", "manual://customer-support/ticketing"),
                        new KnowledgeBaseDocumentDto("kb-doc-refund-direct", "七天无理由退款", "若订单满足七天无理由且未发货，可直接给出退款结论。", "manual://customer-support/refund-direct"),
                        new KnowledgeBaseDocumentDto("kb-doc-refund-review", "发货后退款处理", "若订单已发货或存在争议，需要人工进一步确认。", "manual://customer-support/refund-review")
                    )
                ),
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
            routerSkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "路由 Skill",
            "digest-skill-router-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                null,
                null,
                new SkillConfigDto(
                    "路由技能",
                    "根据用户问题、知识和上下文判断路由方向。",
                    "当你需要做问题分诊时，优先判断是否属于 FAQ、售后策略或人工协同，并输出明确路由依据。"
                )
            )
        );
        seedResourceVersion(
            faqSkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "FAQ Skill",
            "digest-skill-faq-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                null,
                null,
                new SkillConfigDto(
                    "FAQ 技能",
                    "基于知识召回内容提供常规问答回复。",
                    "当问题属于 FAQ 时，优先基于召回到的知识内容直接回答，保持简洁、准确、可执行。"
                )
            )
        );
        seedResourceVersion(
            policySkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "售后策略 Skill",
            "digest-skill-policy-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                null,
                null,
                new SkillConfigDto(
                    "售后策略技能",
                    "结合知识和工具输出判断退款或补偿策略。",
                    "当处理退款、补偿、退货、售后类问题时，结合规则与工具结果给出明确策略建议，并说明是否需要人工复核。"
                )
            )
        );
        seedResourceVersion(
            handoffSkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "人工协同 Skill",
            "digest-skill-handoff-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                null,
                null,
                new SkillConfigDto(
                    "人工协同闭环技能",
                    "根据人工动作、工具结果与上下文生成闭环说明。",
                    "当人工已经介入时，整合人工处理说明、工单结果和当前上下文，生成对用户的最终闭环答复。"
                )
            )
        );
        ResourceVersionDto refundToolVersion = seedResourceVersion(
            refundTool.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "售后策略 Tool",
            "digest-tool-refund-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.TOOL,
                null,
                new ToolConfigDto(
                    List.of(new ToolOperationDto(
                        "evaluate_refund",
                        "根据问题和知识上下文判断退款/补偿策略",
                        "{\"question\":\"string\",\"knowledgeHits\":[\"string\"]}",
                        "{\"eligibility\":\"string\",\"routeKey\":\"string\",\"actionPlan\":\"string\"}"
                    )),
                    ToolProviderType.HTTP,
                    "SERVICE_ACCOUNT",
                    15,
                    "NONE",
                    new HttpToolProviderConfigDto("http://demo.local/skills/refund-policy", "POST"),
                    null
                ),
                null,
                null
            )
        );
        ResourceVersionDto ticketToolVersion = seedResourceVersion(
            ticketTool.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "工单协同 Tool",
            "digest-tool-ticket-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.TOOL,
                null,
                new ToolConfigDto(
                    List.of(
                        new ToolOperationDto(
                            "create_ticket",
                            "创建人工协同工单",
                            "{\"question\":\"string\",\"operator\":\"string\",\"comment\":\"string\"}",
                            "{\"ticketId\":\"string\",\"status\":\"string\",\"detail\":\"string\"}"
                        ),
                        new ToolOperationDto(
                            "append_comment",
                            "为协同工单追加处理备注",
                            "{\"ticketId\":\"string\",\"comment\":\"string\"}",
                            "{\"status\":\"string\",\"detail\":\"string\"}"
                        )
                    ),
                    ToolProviderType.MCP,
                    "NONE",
                    30,
                    "NONE",
                    null,
                    new McpToolProviderConfigDto(
                        "ticketing-server",
                        "STREAMABLE_HTTP",
                        "http://demo.local/mcp/ticketing",
                        "support.ticket",
                        30,
                        Map.of(
                            "create_ticket", "create_ticket",
                            "append_comment", "append_comment"
                        )
                    )
                ),
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
            new AgentExecutionPolicyDto(true, null, "你是问题分诊智能体，负责判断当前问题应进入 FAQ、售后或人工协同路径。", true, kb.id(), 8, List.of(routerSkill.id()), List.of())
        ));
        agents.add(new AgentDto(
            "agent-faq",
            assistant.id(),
            "FAQ 回答智能体",
            "faq",
            "基于知识检索结果输出最终 FAQ 回复。",
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, "你是 FAQ 回答智能体，负责基于知识库给出直接回复。", true, kb.id(), 8, List.of(faqSkill.id()), List.of())
        ));
        agents.add(new AgentDto(
            "agent-policy",
            assistant.id(),
            "售后策略智能体",
            "policy",
            "调用售后策略 Tool，给出退款或补偿结论。",
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, "你是售后策略智能体，负责结合规则与工具结果给出处理建议。", true, kb.id(), 8, List.of(policySkill.id()), List.of(refundTool.id()))
        ));
        agents.add(new AgentDto(
            "agent-coordinator",
            assistant.id(),
            "人工协同闭环智能体",
            "handoff",
            "在人工处理后整理摘要、调用工单 Tool，并生成闭环答复。",
            new AgentExecutionPolicyDto(true, defaultLlmResourceId, "你是人工协同闭环智能体，负责整理人工动作并生成最终回复。", false, null, 12, List.of(handoffSkill.id()), List.of(ticketTool.id()))
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

    private AssistantReleaseDto createAssistantRelease(String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        captureEffectiveResource(snapshotMap, assistant.modelPolicy().providerResourceId(), "ASSISTANT_DEFAULT_MODEL");
        if (assistant.ragPolicy().enabled()) {
            captureEffectiveResource(snapshotMap, assistant.ragPolicy().knowledgeBaseResourceId(), "ASSISTANT_DEFAULT_RAG");
        }

        List<AssistantReleaseAgentDto> releaseAgents = new ArrayList<>();
        for (AgentDto agent : orderAgentsForAssistant(assistantId)) {
            captureEffectiveResource(snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());
            if (agent.executionPolicy().ragEnabled()) {
                captureEffectiveResource(snapshotMap, agent.executionPolicy().knowledgeBaseResourceId(), agent.name());
            }

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
                agent.instructions(),
                agent.executionPolicy(),
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
            if (assistant.ragPolicy().enabled() && resource.id().equals(assistant.ragPolicy().knowledgeBaseResourceId())) {
                references.add(toResourceReference(resource, "ASSISTANT_DEFAULT_KNOWLEDGE_BASE", "ASSISTANT", assistant.id(), assistant.name(), null, null, true));
            }
        }

        for (AgentDto agent : agents) {
            if (resource.id().equals(agent.executionPolicy().modelResourceId())) {
                references.add(toResourceReference(resource, "AGENT_OVERRIDE_MODEL", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (agent.executionPolicy().ragEnabled() && resource.id().equals(agent.executionPolicy().knowledgeBaseResourceId())) {
                references.add(toResourceReference(resource, "AGENT_OVERRIDE_KNOWLEDGE_BASE", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (agent.executionPolicy().skillResourceIds().contains(resource.id())) {
                references.add(toResourceReference(resource, "AGENT_SKILL_ENABLED", "AGENT", agent.id(), agent.name(), null, null, true));
            }
            if (agent.executionPolicy().toolResourceIds().contains(resource.id())) {
                references.add(toResourceReference(resource, "AGENT_TOOL_ENABLED", "AGENT", agent.id(), agent.name(), null, null, true));
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
            case "ASSISTANT_DEFAULT_KNOWLEDGE_BASE" -> "resource is used as assistant default knowledge base: " + reference.sourceName();
            case "AGENT_OVERRIDE_MODEL" -> "resource is used as agent override model: " + reference.sourceName();
            case "AGENT_OVERRIDE_KNOWLEDGE_BASE" -> "resource is used as agent override knowledge base: " + reference.sourceName();
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
            agent.instructions(),
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
        snapshot.resourceVersions().forEach((resourceId, versions) -> {
            ResourceType resourceType = resources.stream()
                .filter(resource -> resource.id().equals(resourceId))
                .map(ResourceDto::type)
                .findFirst()
                .orElse(null);
            resourceVersions.put(
                resourceId,
                versions.stream()
                    .map(version -> new ResourceVersionDto(
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
            agent.instructions(),
            new AgentExecutionPolicyDto(
                normalizedPolicy.inheritAssistantDefaults(),
                normalizedPolicy.modelResourceId(),
                normalizedPolicy.systemPrompt(),
                normalizedPolicy.ragEnabled(),
                normalizedPolicy.knowledgeBaseResourceId(),
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
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(type, normalizeKnowledgeBaseConfig(configuration.knowledgeBase()), null, null, null);
            case TOOL -> new ResourceVersionConfigurationDto(type, null, normalizeToolConfig(configuration.tool()), null, null);
            case LLM_MODEL -> new ResourceVersionConfigurationDto(type, null, null, configuration.llmModel() == null ? defaultConfiguration(type).llmModel() : configuration.llmModel(), null);
            case SKILL -> new ResourceVersionConfigurationDto(type, null, null, null, configuration.skill() == null ? defaultConfiguration(type).skill() : normalizeSkillConfig(configuration.skill()));
        };
    }

    private ResourceVersionConfigurationDto defaultConfiguration(ResourceType type) {
        return switch (type) {
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(
                type,
                new KnowledgeBaseConfigDto(5, List.of()),
                null,
                null,
                null
            );
            case TOOL -> new ResourceVersionConfigurationDto(
                type,
                null,
                defaultToolConfig(),
                null,
                null
            );
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
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
            case SKILL -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                null,
                new SkillConfigDto("新技能", "请填写技能用途说明。", "请填写技能行为说明。")
            );
        };
    }

    private void validateVersionReadyForActivation(ResourceType type, ResourceVersionConfigurationDto configuration) {
        if (type != ResourceType.KNOWLEDGE_BASE) {
            return;
        }
        KnowledgeBaseConfigDto knowledgeBase = configuration == null ? null : configuration.knowledgeBase();
        if (knowledgeBase == null || knowledgeBase.documents() == null || knowledgeBase.documents().isEmpty()) {
            throw new IllegalStateException("knowledge base published version must contain at least one document");
        }
    }

    private KnowledgeBaseConfigDto normalizeKnowledgeBaseConfig(KnowledgeBaseConfigDto configuration) {
        List<KnowledgeBaseDocumentDto> documents = normalizeKnowledgeBaseDocuments(configuration == null ? null : configuration.documents());
        int defaultTopK = configuration == null || configuration.defaultTopK() <= 0 ? 5 : configuration.defaultTopK();

        return new KnowledgeBaseConfigDto(
            defaultTopK,
            List.copyOf(documents)
        );
    }

    private List<KnowledgeBaseDocumentDto> normalizeKnowledgeBaseDocuments(List<KnowledgeBaseDocumentDto> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<KnowledgeBaseDocumentDto> normalized = new ArrayList<>();
        int index = 1;
        for (KnowledgeBaseDocumentDto document : documents) {
            if (document == null) {
                continue;
            }
            String content = normalizeOptionalText(document.content());
            if (content.isBlank()) {
                continue;
            }
            String title = normalizeOptionalText(document.title());
            if (title.isBlank()) {
                title = defaultKnowledgeDocumentTitle(content, index);
            }
            String documentId = normalizeOptionalText(document.id());
            if (documentId.isBlank()) {
                documentId = nextId("kb-doc");
            }
            normalized.add(new KnowledgeBaseDocumentDto(
                documentId,
                title,
                content,
                normalizeOptionalText(document.sourceUri())
            ));
            index += 1;
        }
        return List.copyOf(normalized);
    }

    private String defaultKnowledgeDocumentTitle(String content, int index) {
        String singleLine = content.replace('\n', ' ').trim();
        if (singleLine.isBlank()) {
            return "文档 " + index;
        }
        int maxLength = Math.min(singleLine.length(), 24);
        return singleLine.substring(0, maxLength);
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
            List.of(new ToolOperationDto("invoke", "执行通用工具动作", "{\"input\":\"string\"}", "{\"output\":\"string\"}")),
            ToolProviderType.HTTP,
            "SERVICE_ACCOUNT",
            15,
            "NONE",
            new HttpToolProviderConfigDto("http://demo.local/tools/new-tool", "POST"),
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
            configuration == null || configuration.serverName() == null || configuration.serverName().isBlank() ? "demo-mcp-server" : configuration.serverName(),
            configuration == null || configuration.transport() == null || configuration.transport().isBlank() ? "STREAMABLE_HTTP" : configuration.transport(),
            configuration == null || configuration.connectionUri() == null || configuration.connectionUri().isBlank() ? "http://demo.local/mcp/default" : configuration.connectionUri(),
            configuration == null || configuration.namespace() == null || configuration.namespace().isBlank() ? "default.namespace" : configuration.namespace(),
            configuration == null || configuration.heartbeatSeconds() <= 0 ? 30 : configuration.heartbeatSeconds(),
            Map.copyOf(operationMappings)
        );
    }

    private AssistantModelPolicyDto normalizeAssistantModelPolicy(AssistantModelPolicyDto policy) {
        if (policy == null) {
            return new AssistantModelPolicyDto(resolveDefaultResourceId(ResourceType.LLM_MODEL, defaultLlmResourceId()));
        }
        return new AssistantModelPolicyDto(policy.providerResourceId());
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
            return new AgentExecutionPolicyDto(true, null, "", defaultKnowledgeBaseId != null, defaultKnowledgeBaseId, 8, List.of(), List.of());
        }
        return new AgentExecutionPolicyDto(
            policy.inheritAssistantDefaults(),
            policy.modelResourceId(),
            normalizeOptionalText(policy.systemPrompt()),
            policy.ragEnabled(),
            policy.knowledgeBaseResourceId(),
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
