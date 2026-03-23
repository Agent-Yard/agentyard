package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final List<BusinessDomainDto> domains = new ArrayList<>();
    private final List<ScenarioDto> scenarios = new ArrayList<>();
    private final List<AssistantDto> assistants = new ArrayList<>();
    private final List<AgentDto> agents = new ArrayList<>();
    private final List<ResourceDto> resources = new ArrayList<>();
    private final Map<String, List<ResourceVersionDto>> resourceVersions = new LinkedHashMap<>();
    private final Map<String, List<AssistantReleaseDto>> assistantReleases = new LinkedHashMap<>();
    private final Map<String, AssistantOrchestrationDto> orchestrations = new LinkedHashMap<>();

    public CatalogService() {
        seed();
    }

    public CatalogSummaryDto summary() {
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
        return domains.stream()
            .sorted(Comparator.comparing(BusinessDomainDto::name))
            .map(this::toDomainView)
            .toList();
    }

    public BusinessDomainDto createDomain(CreateDomainRequest request) {
        BusinessDomainDto domain = new BusinessDomainDto(
            nextId("domain"),
            request.name(),
            request.description(),
            List.of(),
            List.of()
        );
        domains.add(domain);
        return toDomainView(domain);
    }

    public List<ScenarioDto> listScenarios() {
        return scenarios.stream()
            .sorted(Comparator.comparing(ScenarioDto::name))
            .map(this::toScenarioView)
            .toList();
    }

    public ScenarioDto getScenario(String scenarioId) {
        return toScenarioView(findScenario(scenarioId));
    }

    public ScenarioDto createScenario(CreateScenarioRequest request) {
        ScenarioDto scenario = new ScenarioDto(
            nextId("scenario"),
            request.domainId(),
            request.name(),
            request.goal(),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of()
        );
        scenarios.add(scenario);
        return toScenarioView(scenario);
    }

    public ScenarioDto updateScenario(String scenarioId, UpdateScenarioRequest request) {
        ScenarioDto existing = findScenario(scenarioId);
        ScenarioDto updated = new ScenarioDto(
            existing.id(),
            existing.domainId(),
            request.name(),
            request.goal(),
            existing.version(),
            existing.assistants()
        );
        replace(scenarios, ScenarioDto::id, updated);
        return toScenarioView(updated);
    }

    public AssistantDto createAssistant(CreateAssistantRequest request) {
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
        return toAssistantView(assistant);
    }

    public AssistantDto updateAssistant(String assistantId, UpdateAssistantRequest request) {
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
        return toAssistantView(updated);
    }

    public List<AssistantDto> listAssistants() {
        return assistants.stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(this::toAssistantView)
            .toList();
    }

    public AgentDto createAgent(CreateAgentRequest request) {
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
        return agent;
    }

    public AgentDto updateAgent(String agentId, UpdateAgentRequest request) {
        AgentDto existing = findAgent(agentId);
        AgentDto updated = new AgentDto(
            existing.id(),
            existing.assistantId(),
            request.name(),
            request.role(),
            request.instructions(),
            existing.bindings(),
            normalizeAgentExecutionPolicy(request.executionPolicy())
        );
        replace(agents, AgentDto::id, updated);
        return updated;
    }

    public AgentDto updateAgentBindings(String agentId, UpdateAgentBindingsRequest request) {
        AgentDto agent = findAgent(agentId);
        Map<String, ResourceBindingDto> existingBindings = agent.bindings().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.resourceVersionId(), item), Map::putAll);

        List<ResourceBindingDto> updatedBindings = request.bindings().stream()
            .distinct()
            .map(binding -> toVersionAnchoredBinding(agentId, existingBindings, binding))
            .toList();

        AgentDto updated = new AgentDto(
            agent.id(),
            agent.assistantId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            updatedBindings,
            agent.executionPolicy()
        );
        replace(agents, AgentDto::id, updated);
        return updated;
    }

    public List<AgentDto> listAgents() {
        return agents.stream()
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();
    }

    public AgentDto getAgent(String agentId) {
        return findAgent(agentId);
    }

    public ResourceDto createResource(CreateResourceRequest request) {
        String resourceId = nextId("resource");
        ResourceDto resource = new ResourceDto(
            resourceId,
            request.domainId(),
            request.name(),
            request.type(),
            request.shareScope(),
            request.ownerType(),
            request.ownerId(),
            request.summary(),
            request.steward(),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            null,
            null,
            List.of()
        );
        resources.add(resource);
        CreateResourceVersionRequest initialVersion = request.initialVersion() == null
            ? new CreateResourceVersionRequest(
                "初始版本",
                "digest-" + resourceId,
                VersionStatus.DRAFT,
                defaultConfiguration(resource.type())
            )
            : new CreateResourceVersionRequest(
                request.initialVersion().summary(),
                request.initialVersion().configDigest(),
                request.initialVersion().status(),
                normalizeConfiguration(resource.type(), request.initialVersion().configuration())
            );
        createResourceVersion(resourceId, initialVersion);
        return toResourceView(resource);
    }

    public List<ResourceVersionDto> listResourceVersions(String resourceId) {
        findResource(resourceId);
        return versionsFor(resourceId);
    }

    public ResourceVersionDto createResourceVersion(String resourceId, CreateResourceVersionRequest request) {
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
        return created;
    }

    public ResourceVersionDto publishResourceVersion(String resourceId, String versionId) {
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
        return updatedVersions.stream().filter(item -> item.id().equals(versionId)).findFirst().orElseThrow();
    }

    public List<ResourceDto> listResources() {
        return resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .toList();
    }

    public List<AssistantOrchestrationDto> listOrchestrations() {
        return assistants.stream()
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(assistant -> getOrCreateOrchestration(assistant.id()))
            .toList();
    }

    public AssistantOrchestrationDto getOrchestration(String assistantId) {
        findAssistant(assistantId);
        return getOrCreateOrchestration(assistantId);
    }

    public AssistantOrchestrationDto saveOrchestration(String assistantId, UpdateOrchestrationRequest request) {
        AssistantDto assistant = findAssistant(assistantId);
        AssistantOrchestrationDto saved = new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            request.executionMode(),
            request.nodes(),
            request.edges()
        );
        orchestrations.put(assistantId, synchronizeOrchestration(saved));
        return orchestrations.get(assistantId);
    }

    public ResourceCenterDto resourceCenter() {
        List<ResourceUsageDto> usages = resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::toResourceView)
            .map(this::buildResourceUsage)
            .toList();
        long domainShared = resources.stream().filter(item -> item.shareScope() == ShareScope.DOMAIN_SHARED).count();
        long privateCount = resources.stream().filter(item -> item.shareScope() == ShareScope.PRIVATE).count();
        return new ResourceCenterDto(resources.size(), Math.toIntExact(domainShared), Math.toIntExact(privateCount), usages);
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

    public ResourceBindingDto bindResource(BindResourceRequest request) {
        AgentDto agent = findAgent(request.consumerId());
        ResourceVersionDto version = effectiveVersion(findResource(request.resourceId()));
        ResourceBindingDto binding = new ResourceBindingDto(
            nextId("binding"),
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
            append(agent.bindings(), binding),
            agent.executionPolicy()
        );
        replace(agents, AgentDto::id, updated);
        return binding;
    }

    private void seed() {
        BusinessDomainDto domain = new BusinessDomainDto(
            "domain-support",
            "智能客服域",
            "用于知识问答与升级处理的 MVP 业务域",
            List.of(),
            List.of()
        );
        domains.add(domain);

        ScenarioDto scenario = new ScenarioDto(
            "scenario-knowledge-escalation",
            domain.id(),
            "知识问答升级处理",
            "回答常见问题，复杂问题自动升级给人工坐席",
            new VersionDto("0.1.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of()
        );
        scenarios.add(scenario);

        AssistantDto knowledgeAssistant = new AssistantDto(
            "assistant-knowledge-escalation",
            scenario.id(),
            "问答升级助手",
            "负责知识检索、答案生成和升级判定",
            new VersionDto("0.1.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of(),
            null,
            List.of(),
            new AssistantModelPolicyDto("resource-llm-openai", "resource-prompt-support", 0.2, 1200),
            new RagPolicyDto(true, "resource-kb-support", 5),
            new MemoryPolicyDto(true, 8)
        );
        AssistantDto aftersalesAssistant = new AssistantDto(
            "assistant-after-sales",
            scenario.id(),
            "售后策略助手",
            "负责退款、补偿和售后政策解释",
            new VersionDto("0.1.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of(),
            null,
            List.of(),
            new AssistantModelPolicyDto("resource-llm-openai", "resource-prompt-after-sales", 0.2, 1200),
            new RagPolicyDto(true, "resource-kb-support", 4),
            new MemoryPolicyDto(true, 8)
        );
        AssistantDto coordinationAssistant = new AssistantDto(
            "assistant-human-coordination",
            scenario.id(),
            "人工协同助手",
            "负责人工接管、工单协同和升级闭环",
            new VersionDto("0.1.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of(),
            null,
            List.of(),
            new AssistantModelPolicyDto("resource-llm-openai", "resource-prompt-coordination", 0.1, 1000),
            new RagPolicyDto(false, "resource-kb-support", 3),
            new MemoryPolicyDto(true, 12)
        );
        assistants.add(knowledgeAssistant);
        assistants.add(aftersalesAssistant);
        assistants.add(coordinationAssistant);

        ResourceDto kb = new ResourceDto(
            "resource-kb-support",
            domain.id(),
            "客服知识库",
            ResourceType.KNOWLEDGE_BASE,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于常见问题检索的知识库",
            "客服知识运营",
            List.of("客服", "FAQ", "知识检索"),
            null,
            null,
            List.of()
        );
        ResourceDto skill = new ResourceDto(
            "resource-skill-answer",
            domain.id(),
            "答案生成 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            knowledgeAssistant.id(),
            "根据检索结果生成可发送答案",
            "问答升级助手团队",
            List.of("回答生成", "文本输出"),
            null,
            null,
            List.of()
        );
        ResourceDto refundSkill = new ResourceDto(
            "resource-skill-refund",
            domain.id(),
            "退款策略 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "ASSISTANT",
            aftersalesAssistant.id(),
            "用于判断退款资格与补偿策略",
            "售后策略团队",
            List.of("退款", "售后策略"),
            null,
            null,
            List.of()
        );
        ResourceDto ticketMcp = new ResourceDto(
            "resource-mcp-ticket",
            domain.id(),
            "工单系统 MCP",
            ResourceType.MCP,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于创建工单和同步人工处理结果",
            "客服平台集成",
            List.of("MCP", "工单", "协同"),
            null,
            null,
            List.of()
        );
        ResourceDto llmModel = new ResourceDto(
            "resource-llm-openai",
            domain.id(),
            "OpenAI 客服主模型",
            ResourceType.LLM_MODEL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于问答、售后和协同场景的默认 LLM 模型资源",
            "平台 AI 工程团队",
            List.of("LLM", "OpenAI", "客服"),
            null,
            null,
            List.of()
        );
        ResourceDto compatibleLlmModel = new ResourceDto(
            "resource-llm-compatible",
            domain.id(),
            "自定义 OpenAI Compatible 模型",
            ResourceType.LLM_MODEL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于接入兼容 OpenAI API 的自定义模型网关或私有化模型服务",
            "平台 AI 工程团队",
            List.of("LLM", "OpenAI-Compatible", "自定义"),
            null,
            null,
            List.of()
        );
        ResourceDto supportPrompt = new ResourceDto(
            "resource-prompt-support",
            domain.id(),
            "问答升级 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            knowledgeAssistant.id(),
            "问答升级助手默认 Prompt 模板",
            "问答升级助手团队",
            List.of("Prompt", "问答"),
            null,
            null,
            List.of()
        );
        ResourceDto afterSalesPrompt = new ResourceDto(
            "resource-prompt-after-sales",
            domain.id(),
            "售后策略 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            aftersalesAssistant.id(),
            "售后策略助手默认 Prompt 模板",
            "售后策略团队",
            List.of("Prompt", "售后"),
            null,
            null,
            List.of()
        );
        ResourceDto coordinationPrompt = new ResourceDto(
            "resource-prompt-coordination",
            domain.id(),
            "人工协同 Prompt",
            ResourceType.PROMPT_TEMPLATE,
            ShareScope.PRIVATE,
            "ASSISTANT",
            coordinationAssistant.id(),
            "人工协同助手默认 Prompt 模板",
            "客服平台集成",
            List.of("Prompt", "协同"),
            null,
            null,
            List.of()
        );
        resources.add(kb);
        resources.add(skill);
        resources.add(refundSkill);
        resources.add(ticketMcp);
        resources.add(llmModel);
        resources.add(compatibleLlmModel);
        resources.add(supportPrompt);
        resources.add(afterSalesPrompt);
        resources.add(coordinationPrompt);

        ResourceVersionDto kbPublished = seedResourceVersion(
            kb.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "客服 FAQ 稳定版",
            "digest-kb-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.KNOWLEDGE_BASE,
                new KnowledgeBaseConfigDto("OBJECT_STORAGE", "minio://knowledge/support-faq", "SCHEDULED", "HYBRID", "text-embedding-3-large", "markdown-512-overlap-80", 5, 1280),
                null,
                null,
                null,
                null
            )
        );
        seedResourceVersion(
            kb.id(),
            "1.1.0",
            VersionStatus.DRAFT,
            "补充密码重置与账号安全条目",
            "digest-kb-v1-1",
            new ResourceVersionConfigurationDto(
                ResourceType.KNOWLEDGE_BASE,
                new KnowledgeBaseConfigDto("OBJECT_STORAGE", "minio://knowledge/support-faq", "SCHEDULED", "HYBRID", "text-embedding-3-large", "markdown-512-overlap-80", 6, 1460),
                null,
                null,
                null,
                null
            )
        );
        ResourceVersionDto skillPublished = seedResourceVersion(
            skill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "答案生成稳定版",
            "digest-answer-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                new SkillConfigDto("HTTP", "https://skill-gateway.internal/answer", "POST", "SERVICE_ACCOUNT", 15, "EXPONENTIAL_BACKOFF", "{question, passages[]}", "{answer, confidence}"),
                null,
                null,
                null
            )
        );
        ResourceVersionDto refundPublished = seedResourceVersion(
            refundSkill.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "退款策略基线版",
            "digest-refund-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.SKILL,
                null,
                new SkillConfigDto("WORKFLOW_ACTIVITY", "activity://refund-policy", "RPC", "SERVICE_ACCOUNT", 20, "FIXED_3_RETRIES", "{orderId, complaintType}", "{eligibility, actionPlan}"),
                null,
                null,
                null
            )
        );
        ResourceVersionDto ticketPublished = seedResourceVersion(
            ticketMcp.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "工单系统集成版",
            "digest-ticket-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.MCP,
                null,
                null,
                new McpConfigDto("ticketing-server", "STREAMABLE_HTTP", "https://mcp-gateway.internal/ticketing", "support.ticket", "API_KEY", 30, List.of("create_ticket", "sync_ticket", "append_comment")),
                null,
                null
            )
        );
        ResourceVersionDto llmPublished = seedResourceVersion(
            llmModel.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "OpenAI 主模型基线版",
            "digest-llm-openai-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.LLM_MODEL,
                null,
                null,
                null,
                new LlmModelConfigDto("OPENAI", "gpt-4.1-mini", "https://api.openai.com/v1", "OPENAI_API_KEY", "lynxus-demo", "support-project", "global", 0.2, 1200),
                null
            )
        );
        seedResourceVersion(
            compatibleLlmModel.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "自定义兼容模型基线版",
            "digest-llm-compatible-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.LLM_MODEL,
                null,
                null,
                null,
                new LlmModelConfigDto(
                    "OPENAI_COMPATIBLE",
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID", "custom-compatible-model"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_BASE_URL", "http://localhost:11434/v1"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR", "OPENAI_COMPATIBLE_API_KEY"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION", "compatible-lab"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_PROJECT", "self-hosted"),
                    envOrDefault("LYNXUS_OPENAI_COMPATIBLE_REGION", "local"),
                    0.2,
                    1200
                ),
                null
            )
        );
        ResourceVersionDto supportPromptPublished = seedResourceVersion(
            supportPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "问答升级默认 Prompt",
            "digest-prompt-support-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto("CHAT", "你是企业客服问答与升级助手。", "用户问题：{{question}}\n召回知识：{{knowledge_context}}\n请输出回答、信心和是否建议升级。", "markdown")
            )
        );
        seedResourceVersion(
            afterSalesPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "售后策略默认 Prompt",
            "digest-prompt-after-sales-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto("CHAT", "你是售后策略智能体。", "用户问题：{{question}}\n请结合规则和工具给出处理建议。", "markdown")
            )
        );
        seedResourceVersion(
            coordinationPrompt.id(),
            "1.0.0",
            VersionStatus.PUBLISHED,
            "人工协同默认 Prompt",
            "digest-prompt-coordination-v1",
            new ResourceVersionConfigurationDto(
                ResourceType.PROMPT_TEMPLATE,
                null,
                null,
                null,
                null,
                new PromptTemplateConfigDto("CHAT", "你是人工协同智能体。", "用户问题：{{question}}\n历史上下文：{{conversation_summary}}\n请输出工单摘要和人工接管建议。", "markdown")
            )
        );

        ResourceBindingDto kbBinding = new ResourceBindingDto("binding-kb-router", kb.id(), kbPublished.id(), kbPublished.version(), "AGENT", "agent-router", Instant.now());
        ResourceBindingDto skillBinding = new ResourceBindingDto("binding-skill-responder", skill.id(), skillPublished.id(), skillPublished.version(), "AGENT", "agent-responder", Instant.now());
        ResourceBindingDto refundBinding = new ResourceBindingDto("binding-skill-refund", refundSkill.id(), refundPublished.id(), refundPublished.version(), "AGENT", "agent-refund-policy", Instant.now());
        ResourceBindingDto ticketBinding = new ResourceBindingDto("binding-mcp-ticket", ticketMcp.id(), ticketPublished.id(), ticketPublished.version(), "AGENT", "agent-human-coordinator", Instant.now());

        agents.add(new AgentDto(
            "agent-router",
            knowledgeAssistant.id(),
            "问题路由智能体",
            "router",
            "识别问题类型，决定直接回答还是进入升级流程",
            List.of(kbBinding),
            new AgentExecutionPolicyDto(true, null, supportPrompt.id(), "判断问题类型，决定走 FAQ、售后或人工升级链路。", true, kb.id(), 8, List.of())
        ));
        agents.add(new AgentDto(
            "agent-responder",
            knowledgeAssistant.id(),
            "回答生成智能体",
            "responder",
            "根据知识库结果生成结构化答案",
            List.of(skillBinding),
            new AgentExecutionPolicyDto(true, llmModel.id(), supportPrompt.id(), "基于检索结果输出专业、简洁、可执行的回答。", true, kb.id(), 8, List.of(skill.id()))
        ));
        agents.add(new AgentDto(
            "agent-escalation",
            knowledgeAssistant.id(),
            "升级判定智能体",
            "escalation",
            "识别是否需要转人工",
            List.of(),
            new AgentExecutionPolicyDto(true, llmModel.id(), supportPrompt.id(), "判断是否需要升级到人工协同，并说明原因。", true, kb.id(), 6, List.of(ticketMcp.id()))
        ));
        agents.add(new AgentDto(
            "agent-refund-policy",
            aftersalesAssistant.id(),
            "退款策略智能体",
            "policy",
            "根据售后规则判断退款、补偿和处理路径",
            List.of(refundBinding),
            new AgentExecutionPolicyDto(true, llmModel.id(), afterSalesPrompt.id(), "根据售后规则和上下文判断退款资格。", true, kb.id(), 8, List.of(refundSkill.id()))
        ));
        agents.add(new AgentDto(
            "agent-after-sales-response",
            aftersalesAssistant.id(),
            "售后答复智能体",
            "responder",
            "生成售后解释、退款反馈和后续动作建议",
            List.of(),
            new AgentExecutionPolicyDto(true, llmModel.id(), afterSalesPrompt.id(), "生成最终售后答复和后续行动说明。", true, kb.id(), 8, List.of())
        ));
        agents.add(new AgentDto(
            "agent-human-coordinator",
            coordinationAssistant.id(),
            "人工协同智能体",
            "handoff",
            "为人工坐席整理上下文并创建协同工单",
            List.of(ticketBinding),
            new AgentExecutionPolicyDto(true, llmModel.id(), coordinationPrompt.id(), "总结上下文并触发人工协同。", false, null, 12, List.of(ticketMcp.id()))
        ));
        agents.add(new AgentDto(
            "agent-resolution-tracker",
            coordinationAssistant.id(),
            "闭环跟踪智能体",
            "tracker",
            "跟踪人工处理结果并生成闭环摘要",
            List.of(),
            new AgentExecutionPolicyDto(true, llmModel.id(), coordinationPrompt.id(), "根据人工处理结果生成闭环摘要。", false, null, 12, List.of(ticketMcp.id()))
        ));

        orchestrations.put(knowledgeAssistant.id(), buildDefaultOrchestration(knowledgeAssistant.id()));
        orchestrations.put(aftersalesAssistant.id(), buildDefaultOrchestration(aftersalesAssistant.id()));
        orchestrations.put(coordinationAssistant.id(), buildDefaultOrchestration(coordinationAssistant.id()));
        createAssistantRelease(knowledgeAssistant.id(), "0.1.0", VersionStatus.PUBLISHED);
        createAssistantRelease(aftersalesAssistant.id(), "0.1.0", VersionStatus.PUBLISHED);
        createAssistantRelease(coordinationAssistant.id(), "0.1.0", VersionStatus.PUBLISHED);
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
        return new ScenarioDto(
            scenario.id(),
            scenario.domainId(),
            scenario.name(),
            scenario.goal(),
            scenario.version(),
            scenarioAssistants
        );
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
            releases.isEmpty() ? null : releases.get(0),
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
            versions.isEmpty() ? null : versions.get(versions.size() - 1),
            versions.stream().filter(item -> item.status() == VersionStatus.PUBLISHED).reduce((__, item) -> item).orElse(null),
            versions
        );
    }

    private List<AgentDto> orderAgentsForAssistant(String assistantId) {
        AssistantOrchestrationDto orchestration = orchestrations.get(assistantId);
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        if (orchestration != null) {
            for (int i = 0; i < orchestration.nodes().size(); i++) {
                orderIndex.put(orchestration.nodes().get(i).agentId(), i);
            }
        }

        return agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator
                .comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE))
                .thenComparing(AgentDto::name))
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

        List<OrchestrationNodeDto> nodes = assistantAgents.stream()
            .map(this::toNode)
            .toList();

        return new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            "SEQUENTIAL_GRAPH",
            nodes,
            buildSequentialEdges(nodes)
        );
    }

    private AssistantOrchestrationDto synchronizeOrchestration(AssistantOrchestrationDto source) {
        AssistantDto assistant = findAssistant(source.assistantId());
        Map<String, OrchestrationNodeDto> existingNodes = source.nodes().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.agentId(), item), Map::putAll);

        List<AgentDto> assistantAgents = orderAgentsForSavedNodes(assistant.id(), existingNodes);
        List<OrchestrationNodeDto> nodes = assistantAgents.stream()
            .map(agent -> {
                OrchestrationNodeDto existing = existingNodes.get(agent.id());
                if (existing == null) {
                    return toNode(agent);
                }
                return new OrchestrationNodeDto(
                    existing.nodeId(),
                    existing.nodeName(),
                    existing.nodeType(),
                    agent.id(),
                    existing.description(),
                    agent.bindings().stream().map(ResourceBindingDto::resourceId).toList()
                );
            })
            .toList();

        Map<String, OrchestrationNodeDto> nodesById = nodes.stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.nodeId(), item), Map::putAll);

        List<OrchestrationEdgeDto> edges = source.edges().stream()
            .filter(edge -> nodesById.containsKey(edge.fromNodeId()) && nodesById.containsKey(edge.toNodeId()))
            .toList();

        if (edges.isEmpty() && nodes.size() > 1) {
            edges = buildSequentialEdges(nodes);
        }

        return new AssistantOrchestrationDto(
            assistant.id(),
            assistant.name(),
            assistant.scenarioId(),
            source.executionMode(),
            nodes,
            edges
        );
    }

    private List<AgentDto> orderAgentsForSavedNodes(String assistantId, Map<String, OrchestrationNodeDto> existingNodes) {
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        int index = 0;
        for (String agentId : existingNodes.keySet()) {
            orderIndex.put(agentId, index++);
        }

        return agents.stream()
            .filter(item -> item.assistantId().equals(assistantId))
            .sorted(Comparator
                .comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE))
                .thenComparing(AgentDto::name))
            .toList();
    }

    private ResourceBindingDto toVersionAnchoredBinding(
        String agentId,
        Map<String, ResourceBindingDto> existingBindings,
        ResourceBindingTarget target
    ) {
        ResourceVersionDto version = findResourceVersion(target.resourceId(), target.resourceVersionId());
        return existingBindings.getOrDefault(
            version.id(),
            new ResourceBindingDto(
                nextId("binding"),
                target.resourceId(),
                version.id(),
                version.version(),
                "AGENT",
                agentId,
                Instant.now()
            )
        );
    }

    private AssistantReleaseDto createAssistantRelease(String assistantId, String releaseVersion, VersionStatus status) {
        AssistantDto assistant = findAssistant(assistantId);
        Map<String, AssistantReleaseResourceDto> snapshotMap = new LinkedHashMap<>();
        capturePolicyResource(snapshotMap, assistant.modelPolicy().providerResourceId(), "ASSISTANT_DEFAULT_MODEL");
        capturePolicyResource(snapshotMap, assistant.modelPolicy().promptTemplateResourceId(), "ASSISTANT_DEFAULT_PROMPT");
        capturePolicyResource(snapshotMap, assistant.ragPolicy().knowledgeBaseResourceId(), "ASSISTANT_DEFAULT_RAG");
        for (AgentDto agent : orderAgentsForAssistant(assistantId)) {
            capturePolicyResource(snapshotMap, agent.executionPolicy().modelResourceId(), agent.name());
            capturePolicyResource(snapshotMap, agent.executionPolicy().promptTemplateResourceId(), agent.name());
            capturePolicyResource(snapshotMap, agent.executionPolicy().knowledgeBaseResourceId(), agent.name());
            for (ResourceBindingDto binding : agent.bindings()) {
                ResourceDto resource = toResourceView(findResource(binding.resourceId()));
                AssistantReleaseResourceDto existing = snapshotMap.get(binding.resourceVersionId());
                if (existing == null) {
                    snapshotMap.put(
                        binding.resourceVersionId(),
                        new AssistantReleaseResourceDto(
                            resource.id(),
                            resource.name(),
                            resource.type().name(),
                            binding.resourceVersionId(),
                            binding.resourceVersion(),
                            List.of(agent.name())
                        )
                    );
                    continue;
                }

                snapshotMap.put(
                    binding.resourceVersionId(),
                    new AssistantReleaseResourceDto(
                        existing.resourceId(),
                        existing.resourceName(),
                        existing.resourceType(),
                        existing.resourceVersionId(),
                        existing.resourceVersion(),
                        append(existing.boundAgents(), agent.name())
                    )
                );
            }
        }
        List<AssistantReleaseResourceDto> snapshotResources = snapshotMap.values().stream().toList();

        AssistantReleaseDto release = new AssistantReleaseDto(
            nextId("assistant-release"),
            assistant.id(),
            releaseVersion,
            status,
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null,
            snapshotResources
        );
        List<AssistantReleaseDto> releases = new ArrayList<>(assistantReleases.getOrDefault(assistantId, List.of()));
        releases.add(release);
        assistantReleases.put(assistantId, releases);
        return release;
    }

    private void capturePolicyResource(Map<String, AssistantReleaseResourceDto> snapshotMap, String resourceId, String boundAgent) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = toResourceView(findResource(resourceId));
        ResourceVersionDto version = effectiveVersion(resource);
        AssistantReleaseResourceDto existing = snapshotMap.get(version.id());
        if (existing == null) {
            snapshotMap.put(
                version.id(),
                new AssistantReleaseResourceDto(
                    resource.id(),
                    resource.name(),
                    resource.type().name(),
                    version.id(),
                    version.version(),
                    List.of(boundAgent)
                )
            );
            return;
        }
        snapshotMap.put(
            version.id(),
            new AssistantReleaseResourceDto(
                existing.resourceId(),
                existing.resourceName(),
                existing.resourceType(),
                existing.resourceVersionId(),
                existing.resourceVersion(),
                append(existing.boundAgents(), boundAgent)
            )
        );
    }

    private ResourceUsageDto buildResourceUsage(ResourceDto resource) {
        List<String> boundAgents = agents.stream()
            .filter(agent -> agent.bindings().stream().anyMatch(binding -> binding.resourceId().equals(resource.id())))
            .map(AgentDto::name)
            .sorted()
            .toList();

        List<String> boundAssistants = assistants.stream()
            .filter(assistant -> agents.stream()
                .filter(agent -> agent.assistantId().equals(assistant.id()))
                .anyMatch(agent -> agent.bindings().stream().anyMatch(binding -> binding.resourceId().equals(resource.id()))))
            .map(AssistantDto::name)
            .sorted()
            .toList();

        List<String> bindingAnchors = agents.stream()
            .flatMap(agent -> agent.bindings().stream()
                .filter(binding -> binding.resourceId().equals(resource.id()))
                .map(binding -> agent.name() + " -> " + binding.resourceVersion()))
            .sorted()
            .toList();

        String ownerLabel = resource.ownerType() + ":" + resource.ownerId();
        return new ResourceUsageDto(
            resource.id(),
            resource.name(),
            resource.type(),
            resource.shareScope(),
            ownerLabel,
            resource.latestVersion() == null ? null : resource.latestVersion().version(),
            resource.effectiveVersion() == null ? null : resource.effectiveVersion().version(),
            boundAgents,
            boundAssistants,
            bindingAnchors
        );
    }

    private OrchestrationNodeDto toNode(AgentDto agent) {
        return new OrchestrationNodeDto(
            "node-" + agent.id(),
            agent.name(),
            "AGENT",
            agent.id(),
            agent.executionPolicy().inlinePrompt() == null || agent.executionPolicy().inlinePrompt().isBlank()
                ? agent.instructions()
                : agent.executionPolicy().inlinePrompt(),
            agent.bindings().stream().map(ResourceBindingDto::resourceId).toList()
        );
    }

    private List<OrchestrationEdgeDto> buildSequentialEdges(List<OrchestrationNodeDto> nodes) {
        List<OrchestrationEdgeDto> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            OrchestrationNodeDto current = nodes.get(i);
            OrchestrationNodeDto next = nodes.get(i + 1);
            edges.add(new OrchestrationEdgeDto(
                "edge-" + current.nodeId() + "-" + next.nodeId(),
                current.nodeId(),
                next.nodeId(),
                i == nodes.size() - 2 ? "升级判定或结束" : "标准编排流转",
                i == nodes.size() - 2 ? "conditional-handoff" : "direct-handoff"
            ));
        }
        return edges;
    }

    private BusinessDomainDto findDomain(String domainId) {
        return domains.stream().filter(item -> item.id().equals(domainId)).findFirst().orElseThrow();
    }

    private ScenarioDto findScenario(String scenarioId) {
        return scenarios.stream().filter(item -> item.id().equals(scenarioId)).findFirst().orElseThrow();
    }

    private ResourceDto findResource(String resourceId) {
        return resources.stream().filter(item -> item.id().equals(resourceId)).findFirst().orElseThrow();
    }

    private AssistantDto findAssistant(String assistantId) {
        return assistants.stream().filter(item -> item.id().equals(assistantId)).findFirst().orElseThrow();
    }

    private AgentDto findAgent(String agentId) {
        return agents.stream().filter(item -> item.id().equals(agentId)).findFirst().orElseThrow();
    }

    private ResourceVersionDto findResourceVersion(String resourceId, String versionId) {
        return versionsFor(resourceId).stream().filter(item -> item.id().equals(versionId)).findFirst().orElseThrow();
    }

    private List<ResourceVersionDto> versionsFor(String resourceId) {
        return resourceVersions.getOrDefault(resourceId, List.of()).stream()
            .sorted(Comparator.comparing(ResourceVersionDto::createdAt))
            .toList();
    }

    private ResourceVersionDto effectiveVersion(ResourceDto resource) {
        return versionsFor(resource.id()).stream()
            .filter(item -> item.status() == VersionStatus.PUBLISHED)
            .findFirst()
            .orElseGet(() -> versionsFor(resource.id()).stream().findFirst().orElseThrow());
    }

    private String nextAssistantReleaseVersion(String assistantId) {
        List<AssistantReleaseDto> releases = assistantReleases.getOrDefault(assistantId, List.of()).stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt))
            .toList();
        if (releases.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = releases.get(releases.size() - 1).releaseVersion().split("\\.");
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
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(
                type,
                configuration.knowledgeBase() == null ? defaultConfiguration(type).knowledgeBase() : configuration.knowledgeBase(),
                null,
                null,
                null,
                null
            );
            case SKILL -> new ResourceVersionConfigurationDto(
                type,
                null,
                configuration.skill() == null ? defaultConfiguration(type).skill() : configuration.skill(),
                null,
                null,
                null
            );
            case MCP -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                configuration.mcp() == null ? defaultConfiguration(type).mcp() : configuration.mcp(),
                null,
                null
            );
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                null,
                configuration.llmModel() == null ? defaultConfiguration(type).llmModel() : configuration.llmModel(),
                null
            );
            case PROMPT_TEMPLATE -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                null,
                null,
                configuration.promptTemplate() == null ? defaultConfiguration(type).promptTemplate() : configuration.promptTemplate()
            );
        };
    }

    private ResourceVersionConfigurationDto defaultConfiguration(ResourceType type) {
        return switch (type) {
            case KNOWLEDGE_BASE -> new ResourceVersionConfigurationDto(
                type,
                new KnowledgeBaseConfigDto(
                    "OBJECT_STORAGE",
                    "minio://knowledge/new-resource",
                    "MANUAL",
                    "HYBRID",
                    "text-embedding-3-large",
                    "markdown-512-overlap-80",
                    5,
                    0
                ),
                null,
                null,
                null,
                null
            );
            case SKILL -> new ResourceVersionConfigurationDto(
                type,
                null,
                new SkillConfigDto(
                    "HTTP",
                    "https://skill-gateway.internal/new-skill",
                    "POST",
                    "SERVICE_ACCOUNT",
                    15,
                    "EXPONENTIAL_BACKOFF",
                    "{input}",
                    "{output}"
                ),
                null,
                null,
                null
            );
            case MCP -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                new McpConfigDto(
                    "new-mcp-server",
                    "STREAMABLE_HTTP",
                    "https://mcp-gateway.internal/new-server",
                    "default.namespace",
                    "API_KEY",
                    30,
                    List.of("tool_a", "tool_b")
                ),
                null,
                null
            );
            case LLM_MODEL -> new ResourceVersionConfigurationDto(
                type,
                null,
                null,
                null,
                new LlmModelConfigDto(
                    "OPENAI",
                    "gpt-4.1-mini",
                    "https://api.openai.com/v1",
                    "OPENAI_API_KEY",
                    "lynxus-demo",
                    "default-project",
                    "global",
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
                new PromptTemplateConfigDto(
                    "CHAT",
                    "你是企业级智能体平台中的执行智能体，请基于知识和工具输出结构化且可执行的结果。",
                    "用户问题：{{question}}\n\n召回知识：{{knowledge_context}}\n\n请给出回答和下一步动作建议。",
                    "markdown"
                )
            );
        };
    }

    private AssistantModelPolicyDto normalizeAssistantModelPolicy(AssistantModelPolicyDto policy) {
        if (policy == null) {
            return new AssistantModelPolicyDto("resource-llm-openai", "resource-prompt-support", 0.2, 1200);
        }
        return new AssistantModelPolicyDto(
            policy.providerResourceId(),
            policy.promptTemplateResourceId(),
            policy.temperature(),
            policy.maxTokens()
        );
    }

    private RagPolicyDto normalizeRagPolicy(RagPolicyDto policy) {
        if (policy == null) {
            return new RagPolicyDto(true, "resource-kb-support", 5);
        }
        return new RagPolicyDto(policy.enabled(), policy.knowledgeBaseResourceId(), policy.topK());
    }

    private MemoryPolicyDto normalizeMemoryPolicy(MemoryPolicyDto policy) {
        if (policy == null) {
            return new MemoryPolicyDto(true, 8);
        }
        return new MemoryPolicyDto(policy.enabled(), policy.windowSize());
    }

    private AgentExecutionPolicyDto normalizeAgentExecutionPolicy(AgentExecutionPolicyDto policy) {
        if (policy == null) {
            return new AgentExecutionPolicyDto(true, null, null, "", true, "resource-kb-support", 8, List.of());
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

    private String nextResourceVersion(List<ResourceVersionDto> versions) {
        if (versions.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = versions.get(versions.size() - 1).version().split("\\.");
        int patch = Integer.parseInt(segments[2]) + 1;
        return segments[0] + "." + segments[1] + "." + patch;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<ResourceBindingDto> append(List<ResourceBindingDto> bindings, ResourceBindingDto binding) {
        List<ResourceBindingDto> updated = new ArrayList<>(bindings);
        updated.add(binding);
        return updated;
    }

    private static List<String> append(List<String> items, String item) {
        List<String> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static <T, K> void replace(List<T> items, Function<T, K> keyExtractor, T replacement) {
        K replacementKey = keyExtractor.apply(replacement);
        items.removeIf(item -> keyExtractor.apply(item).equals(replacementKey));
        items.add(replacement);
    }
}
