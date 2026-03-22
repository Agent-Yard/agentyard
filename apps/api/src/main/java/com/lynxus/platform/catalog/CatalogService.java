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
    private final List<AgentGroupDto> agentGroups = new ArrayList<>();
    private final List<AgentDto> agents = new ArrayList<>();
    private final List<ResourceDto> resources = new ArrayList<>();
    private final Map<String, AgentOrchestrationDto> orchestrations = new LinkedHashMap<>();

    public CatalogService() {
        seed();
    }

    public CatalogSummaryDto summary() {
        return new CatalogSummaryDto(
            listDomains(),
            listScenarios(),
            listAgentGroups(),
            listAgents(),
            listResources(),
            listOrchestrations(),
            resourceCenter()
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
            existing.agentGroups()
        );
        replace(scenarios, ScenarioDto::id, updated);
        return toScenarioView(updated);
    }

    public AgentGroupDto createAgentGroup(CreateAgentGroupRequest request) {
        AgentGroupDto agentGroup = new AgentGroupDto(
            nextId("agent-group"),
            request.scenarioId(),
            request.name(),
            request.description(),
            new VersionDto("0.1.0", VersionStatus.DRAFT, Instant.now()),
            List.of()
        );
        agentGroups.add(agentGroup);
        return toAgentGroupView(agentGroup);
    }

    public AgentGroupDto updateAgentGroup(String agentGroupId, UpdateAgentGroupRequest request) {
        AgentGroupDto existing = findAgentGroup(agentGroupId);
        VersionDto version = new VersionDto(
            existing.version().version(),
            request.status() == null ? existing.version().status() : request.status(),
            Instant.now()
        );
        AgentGroupDto updated = new AgentGroupDto(
            existing.id(),
            existing.scenarioId(),
            request.name(),
            request.description(),
            version,
            existing.agents()
        );
        replace(agentGroups, AgentGroupDto::id, updated);
        return toAgentGroupView(updated);
    }

    public List<AgentGroupDto> listAgentGroups() {
        return agentGroups.stream()
            .sorted(Comparator.comparing(AgentGroupDto::name))
            .map(this::toAgentGroupView)
            .toList();
    }

    public AgentDto createAgent(CreateAgentRequest request) {
        AgentDto agent = new AgentDto(
            nextId("agent"),
            request.agentGroupId(),
            request.name(),
            request.role(),
            request.instructions(),
            List.of()
        );
        agents.add(agent);
        return agent;
    }

    public AgentDto updateAgent(String agentId, UpdateAgentRequest request) {
        AgentDto existing = findAgent(agentId);
        AgentDto updated = new AgentDto(
            existing.id(),
            existing.agentGroupId(),
            request.name(),
            request.role(),
            request.instructions(),
            existing.bindings()
        );
        replace(agents, AgentDto::id, updated);
        return updated;
    }

    public AgentDto updateAgentBindings(String agentId, UpdateAgentBindingsRequest request) {
        AgentDto agent = findAgent(agentId);
        Map<String, ResourceBindingDto> existingBindings = agent.bindings().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.resourceId(), item), Map::putAll);

        List<ResourceBindingDto> updatedBindings = request.resourceIds().stream()
            .distinct()
            .map(resourceId -> existingBindings.getOrDefault(
                resourceId,
                new ResourceBindingDto(nextId("binding"), resourceId, "AGENT", agentId, Instant.now())
            ))
            .toList();

        AgentDto updated = new AgentDto(
            agent.id(),
            agent.agentGroupId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            updatedBindings
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
        ResourceDto resource = new ResourceDto(
            nextId("resource"),
            request.domainId(),
            request.name(),
            request.type(),
            request.shareScope(),
            request.ownerType(),
            request.ownerId(),
            request.summary()
        );
        resources.add(resource);
        return resource;
    }

    public List<ResourceDto> listResources() {
        return resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .toList();
    }

    public List<AgentOrchestrationDto> listOrchestrations() {
        return agentGroups.stream()
            .sorted(Comparator.comparing(AgentGroupDto::name))
            .map(group -> getOrCreateOrchestration(group.id()))
            .toList();
    }

    public AgentOrchestrationDto getOrchestration(String agentGroupId) {
        findAgentGroup(agentGroupId);
        return getOrCreateOrchestration(agentGroupId);
    }

    public AgentOrchestrationDto saveOrchestration(String agentGroupId, UpdateOrchestrationRequest request) {
        AgentGroupDto group = findAgentGroup(agentGroupId);
        AgentOrchestrationDto saved = new AgentOrchestrationDto(
            group.id(),
            group.name(),
            group.scenarioId(),
            request.executionMode(),
            request.nodes(),
            request.edges()
        );
        orchestrations.put(agentGroupId, synchronizeOrchestration(saved));
        return orchestrations.get(agentGroupId);
    }

    public ResourceCenterDto resourceCenter() {
        List<ResourceUsageDto> usages = resources.stream()
            .sorted(Comparator.comparing(ResourceDto::name))
            .map(this::buildResourceUsage)
            .toList();
        long domainShared = resources.stream().filter(item -> item.shareScope() == ShareScope.DOMAIN_SHARED).count();
        long privateCount = resources.stream().filter(item -> item.shareScope() == ShareScope.PRIVATE).count();
        return new ResourceCenterDto(resources.size(), Math.toIntExact(domainShared), Math.toIntExact(privateCount), usages);
    }

    public ResourceBindingDto bindResource(BindResourceRequest request) {
        AgentDto agent = findAgent(request.consumerId());
        ResourceBindingDto binding = new ResourceBindingDto(
            nextId("binding"),
            request.resourceId(),
            request.consumerType(),
            request.consumerId(),
            Instant.now()
        );
        AgentDto updated = new AgentDto(
            agent.id(),
            agent.agentGroupId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            append(agent.bindings(), binding)
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

        AgentGroupDto agentGroup = new AgentGroupDto(
            "agent-group-knowledge-escalation",
            scenario.id(),
            "问答升级智能体组",
            "负责知识检索、答案生成和升级判定",
            new VersionDto("0.1.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of()
        );
        agentGroups.add(agentGroup);

        ResourceDto kb = new ResourceDto(
            "resource-kb-support",
            domain.id(),
            "客服知识库",
            ResourceType.KNOWLEDGE_BASE,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            domain.id(),
            "用于常见问题检索的知识库"
        );
        ResourceDto skill = new ResourceDto(
            "resource-skill-answer",
            domain.id(),
            "答案生成 Skill",
            ResourceType.SKILL,
            ShareScope.PRIVATE,
            "AGENT_GROUP",
            agentGroup.id(),
            "根据检索结果生成可发送答案"
        );
        resources.add(kb);
        resources.add(skill);

        ResourceBindingDto kbBinding = new ResourceBindingDto("binding-kb-router", kb.id(), "AGENT", "agent-router", Instant.now());
        ResourceBindingDto skillBinding = new ResourceBindingDto("binding-skill-responder", skill.id(), "AGENT", "agent-responder", Instant.now());

        agents.add(new AgentDto(
            "agent-router",
            agentGroup.id(),
            "问题路由智能体",
            "router",
            "识别问题类型，决定直接回答还是进入升级流程",
            List.of(kbBinding)
        ));
        agents.add(new AgentDto(
            "agent-responder",
            agentGroup.id(),
            "回答生成智能体",
            "responder",
            "根据知识库结果生成结构化答案",
            List.of(skillBinding)
        ));
        agents.add(new AgentDto(
            "agent-escalation",
            agentGroup.id(),
            "升级判定智能体",
            "escalation",
            "识别是否需要转人工",
            List.of()
        ));

        orchestrations.put(agentGroup.id(), buildDefaultOrchestration(agentGroup.id()));
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
            .toList();
        return new BusinessDomainDto(domain.id(), domain.name(), domain.description(), domainScenarios, domainResources);
    }

    private ScenarioDto toScenarioView(ScenarioDto scenario) {
        List<AgentGroupDto> scenarioGroups = agentGroups.stream()
            .filter(item -> item.scenarioId().equals(scenario.id()))
            .sorted(Comparator.comparing(AgentGroupDto::name))
            .map(this::toAgentGroupView)
            .toList();
        return new ScenarioDto(
            scenario.id(),
            scenario.domainId(),
            scenario.name(),
            scenario.goal(),
            scenario.version(),
            scenarioGroups
        );
    }

    private AgentGroupDto toAgentGroupView(AgentGroupDto group) {
        List<AgentDto> groupAgents = orderAgentsForGroup(group.id());
        return new AgentGroupDto(
            group.id(),
            group.scenarioId(),
            group.name(),
            group.description(),
            group.version(),
            groupAgents
        );
    }

    private List<AgentDto> orderAgentsForGroup(String agentGroupId) {
        AgentOrchestrationDto orchestration = orchestrations.get(agentGroupId);
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        if (orchestration != null) {
            for (int i = 0; i < orchestration.nodes().size(); i++) {
                orderIndex.put(orchestration.nodes().get(i).agentId(), i);
            }
        }

        return agents.stream()
            .filter(item -> item.agentGroupId().equals(agentGroupId))
            .sorted(Comparator
                .comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE))
                .thenComparing(AgentDto::name))
            .toList();
    }

    private AgentOrchestrationDto getOrCreateOrchestration(String agentGroupId) {
        AgentOrchestrationDto current = orchestrations.computeIfAbsent(agentGroupId, this::buildDefaultOrchestration);
        AgentOrchestrationDto synced = synchronizeOrchestration(current);
        orchestrations.put(agentGroupId, synced);
        return synced;
    }

    private AgentOrchestrationDto buildDefaultOrchestration(String agentGroupId) {
        AgentGroupDto group = findAgentGroup(agentGroupId);
        List<AgentDto> groupAgents = agents.stream()
            .filter(item -> item.agentGroupId().equals(agentGroupId))
            .sorted(Comparator.comparing(AgentDto::name))
            .toList();

        List<OrchestrationNodeDto> nodes = groupAgents.stream()
            .map(this::toNode)
            .toList();

        return new AgentOrchestrationDto(
            group.id(),
            group.name(),
            group.scenarioId(),
            "SEQUENTIAL_GRAPH",
            nodes,
            buildSequentialEdges(nodes)
        );
    }

    private AgentOrchestrationDto synchronizeOrchestration(AgentOrchestrationDto source) {
        AgentGroupDto group = findAgentGroup(source.agentGroupId());
        Map<String, OrchestrationNodeDto> existingNodes = source.nodes().stream()
            .collect(LinkedHashMap::new, (map, item) -> map.put(item.agentId(), item), Map::putAll);

        List<AgentDto> groupAgents = orderAgentsForSavedNodes(group.id(), existingNodes);
        List<OrchestrationNodeDto> nodes = groupAgents.stream()
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

        return new AgentOrchestrationDto(
            group.id(),
            group.name(),
            group.scenarioId(),
            source.executionMode(),
            nodes,
            edges
        );
    }

    private List<AgentDto> orderAgentsForSavedNodes(String agentGroupId, Map<String, OrchestrationNodeDto> existingNodes) {
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        int index = 0;
        for (String agentId : existingNodes.keySet()) {
            orderIndex.put(agentId, index++);
        }

        return agents.stream()
            .filter(item -> item.agentGroupId().equals(agentGroupId))
            .sorted(Comparator
                .comparingInt((AgentDto item) -> orderIndex.getOrDefault(item.id(), Integer.MAX_VALUE))
                .thenComparing(AgentDto::name))
            .toList();
    }

    private ResourceUsageDto buildResourceUsage(ResourceDto resource) {
        List<String> boundAgents = agents.stream()
            .filter(agent -> agent.bindings().stream().anyMatch(binding -> binding.resourceId().equals(resource.id())))
            .map(AgentDto::name)
            .sorted()
            .toList();

        List<String> boundAgentGroups = agentGroups.stream()
            .filter(group -> agents.stream()
                .filter(agent -> agent.agentGroupId().equals(group.id()))
                .anyMatch(agent -> agent.bindings().stream().anyMatch(binding -> binding.resourceId().equals(resource.id()))))
            .map(AgentGroupDto::name)
            .sorted()
            .toList();

        String ownerLabel = resource.ownerType() + ":" + resource.ownerId();
        return new ResourceUsageDto(
            resource.id(),
            resource.name(),
            resource.type(),
            resource.shareScope(),
            ownerLabel,
            boundAgents,
            boundAgentGroups
        );
    }

    private OrchestrationNodeDto toNode(AgentDto agent) {
        return new OrchestrationNodeDto(
            "node-" + agent.id(),
            agent.name(),
            "AGENT",
            agent.id(),
            agent.instructions(),
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

    private AgentGroupDto findAgentGroup(String agentGroupId) {
        return agentGroups.stream().filter(item -> item.id().equals(agentGroupId)).findFirst().orElseThrow();
    }

    private AgentDto findAgent(String agentId) {
        return agents.stream().filter(item -> item.id().equals(agentId)).findFirst().orElseThrow();
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<ResourceBindingDto> append(List<ResourceBindingDto> bindings, ResourceBindingDto binding) {
        List<ResourceBindingDto> updated = new ArrayList<>(bindings);
        updated.add(binding);
        return updated;
    }

    private static <T, K> void replace(List<T> items, Function<T, K> keyExtractor, T replacement) {
        K replacementKey = keyExtractor.apply(replacement);
        items.removeIf(item -> keyExtractor.apply(item).equals(replacementKey));
        items.add(replacement);
    }
}
