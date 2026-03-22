package com.lynxus.platform.catalog;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.time.Instant;
import java.util.List;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record BusinessDomainDto(
        String id,
        String name,
        String description,
        List<ScenarioDto> scenarios,
        List<ResourceDto> resources
    ) {
    }

    public record ScenarioDto(
        String id,
        String domainId,
        String name,
        String goal,
        VersionDto version,
        List<AgentGroupDto> agentGroups
    ) {
    }

    public record AgentGroupDto(
        String id,
        String scenarioId,
        String name,
        String description,
        VersionDto version,
        List<AgentDto> agents
    ) {
    }

    public record AgentDto(
        String id,
        String agentGroupId,
        String name,
        String role,
        String instructions,
        List<ResourceBindingDto> bindings
    ) {
    }

    public record ResourceDto(
        String id,
        String domainId,
        String name,
        ResourceType type,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary
    ) {
    }

    public record ResourceBindingDto(
        String id,
        String resourceId,
        String consumerType,
        String consumerId,
        Instant createdAt
    ) {
    }

    public record VersionDto(
        String version,
        VersionStatus status,
        Instant updatedAt
    ) {
    }

    public record AgentOrchestrationDto(
        String agentGroupId,
        String agentGroupName,
        String scenarioId,
        String executionMode,
        List<OrchestrationNodeDto> nodes,
        List<OrchestrationEdgeDto> edges
    ) {
    }

    public record OrchestrationNodeDto(
        String nodeId,
        String nodeName,
        String nodeType,
        String agentId,
        String description,
        List<String> resourceIds
    ) {
    }

    public record OrchestrationEdgeDto(
        String edgeId,
        String fromNodeId,
        String toNodeId,
        String condition,
        String handoffPolicy
    ) {
    }

    public record ResourceUsageDto(
        String resourceId,
        String resourceName,
        ResourceType type,
        ShareScope shareScope,
        String ownerLabel,
        List<String> boundAgents,
        List<String> boundAgentGroups
    ) {
    }

    public record ResourceCenterDto(
        int totalResources,
        int domainSharedResources,
        int privateResources,
        List<ResourceUsageDto> usages
    ) {
    }

    public record CreateDomainRequest(String name, String description) {
    }

    public record CreateScenarioRequest(String domainId, String name, String goal) {
    }

    public record UpdateScenarioRequest(String name, String goal) {
    }

    public record CreateAgentGroupRequest(String scenarioId, String name, String description) {
    }

    public record UpdateAgentGroupRequest(String name, String description, VersionStatus status) {
    }

    public record CreateAgentRequest(String agentGroupId, String name, String role, String instructions) {
    }

    public record UpdateAgentRequest(String name, String role, String instructions) {
    }

    public record UpdateAgentBindingsRequest(List<String> resourceIds) {
    }

    public record CreateResourceRequest(
        String domainId,
        String name,
        ResourceType type,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary
    ) {
    }

    public record BindResourceRequest(String resourceId, String consumerType, String consumerId) {
    }

    public record UpdateOrchestrationRequest(
        String executionMode,
        List<OrchestrationNodeDto> nodes,
        List<OrchestrationEdgeDto> edges
    ) {
    }

    public record CatalogSummaryDto(
        List<BusinessDomainDto> domains,
        List<ScenarioDto> scenarios,
        List<AgentGroupDto> agentGroups,
        List<AgentDto> agents,
        List<ResourceDto> resources,
        List<AgentOrchestrationDto> orchestrations,
        ResourceCenterDto resourceCenter
    ) {
    }
}
