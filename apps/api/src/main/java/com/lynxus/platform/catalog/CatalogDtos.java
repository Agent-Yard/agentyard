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
        List<AssistantDto> assistants
    ) {
    }

    public record AssistantDto(
        String id,
        String scenarioId,
        String name,
        String description,
        VersionDto version,
        List<AgentDto> agents,
        AssistantReleaseDto currentRelease,
        List<AssistantReleaseDto> releases,
        AssistantModelPolicyDto modelPolicy,
        RagPolicyDto ragPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
    }

    public record AssistantReleaseDto(
        String id,
        String assistantId,
        String releaseVersion,
        VersionStatus status,
        Instant createdAt,
        Instant publishedAt,
        List<AssistantReleaseResourceDto> resources
    ) {
    }

    public record AssistantReleaseResourceDto(
        String resourceId,
        String resourceName,
        String resourceType,
        String resourceVersionId,
        String resourceVersion,
        List<String> boundAgents
    ) {
    }

    public record AgentDto(
        String id,
        String assistantId,
        String name,
        String role,
        String instructions,
        List<ResourceBindingDto> bindings,
        AgentExecutionPolicyDto executionPolicy
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
        String summary,
        String steward,
        List<String> tags,
        ResourceVersionDto latestVersion,
        ResourceVersionDto effectiveVersion,
        List<ResourceVersionDto> versions
    ) {
    }

    public record ResourceBindingDto(
        String id,
        String resourceId,
        String resourceVersionId,
        String resourceVersion,
        String consumerType,
        String consumerId,
        Instant createdAt
    ) {
    }

    public record ResourceVersionDto(
        String id,
        String resourceId,
        String version,
        VersionStatus status,
        String summary,
        String configDigest,
        Instant createdAt,
        Instant publishedAt,
        ResourceVersionConfigurationDto configuration
    ) {
    }

    public record ResourceVersionConfigurationDto(
        ResourceType type,
        KnowledgeBaseConfigDto knowledgeBase,
        SkillConfigDto skill,
        McpConfigDto mcp,
        LlmModelConfigDto llmModel,
        PromptTemplateConfigDto promptTemplate
    ) {
    }

    public record KnowledgeBaseConfigDto(
        String sourceType,
        String sourceLocation,
        String syncMode,
        String retrievalMode,
        String embeddingModel,
        String chunkStrategy,
        int defaultTopK,
        int documentCount
    ) {
    }

    public record SkillConfigDto(
        String runtime,
        String endpoint,
        String method,
        String authType,
        int timeoutSeconds,
        String retryPolicy,
        String inputSchema,
        String outputSchema
    ) {
    }

    public record McpConfigDto(
        String serverName,
        String transport,
        String connectionUri,
        String namespace,
        String authType,
        int heartbeatSeconds,
        List<String> exposedTools
    ) {
    }

    public record LlmModelConfigDto(
        String providerType,
        String modelId,
        String baseUrl,
        String apiKeyEnvVar,
        String organization,
        String project,
        String region,
        double temperature,
        int maxTokens
    ) {
    }

    public record PromptTemplateConfigDto(
        String templateType,
        String systemPrompt,
        String userPromptTemplate,
        String responseFormat
    ) {
    }

    public record AssistantModelPolicyDto(
        String providerResourceId,
        String promptTemplateResourceId,
        double temperature,
        int maxTokens
    ) {
    }

    public record RagPolicyDto(
        boolean enabled,
        String knowledgeBaseResourceId,
        int topK
    ) {
    }

    public record MemoryPolicyDto(
        boolean enabled,
        int windowSize
    ) {
    }

    public record AgentExecutionPolicyDto(
        boolean inheritAssistantDefaults,
        String modelResourceId,
        String promptTemplateResourceId,
        String inlinePrompt,
        boolean ragEnabled,
        String knowledgeBaseResourceId,
        int memoryWindowSize,
        List<String> toolResourceIds
    ) {
    }

    public record ResourceBlueprintDto(
        ResourceType type,
        String label,
        String description,
        List<String> maintainedFields,
        ResourceVersionConfigurationDto defaultConfiguration
    ) {
    }

    public record VersionDto(
        String version,
        VersionStatus status,
        Instant updatedAt
    ) {
    }

    public record AssistantOrchestrationDto(
        String assistantId,
        String assistantName,
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
        String latestVersion,
        String effectiveVersion,
        List<String> boundAgents,
        List<String> boundAssistants,
        List<String> bindingAnchors
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

    public record CreateAssistantRequest(
        String scenarioId,
        String name,
        String description,
        AssistantModelPolicyDto modelPolicy,
        RagPolicyDto ragPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
    }

    public record UpdateAssistantRequest(
        String name,
        String description,
        VersionStatus status,
        AssistantModelPolicyDto modelPolicy,
        RagPolicyDto ragPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
    }

    public record CreateAgentRequest(
        String assistantId,
        String name,
        String role,
        String instructions,
        AgentExecutionPolicyDto executionPolicy
    ) {
    }

    public record UpdateAgentRequest(
        String name,
        String role,
        String instructions,
        AgentExecutionPolicyDto executionPolicy
    ) {
    }

    public record ResourceBindingTarget(String resourceId, String resourceVersionId) {
    }

    public record UpdateAgentBindingsRequest(List<ResourceBindingTarget> bindings) {
    }

    public record CreateResourceRequest(
        String domainId,
        String name,
        ResourceType type,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary,
        String steward,
        List<String> tags,
        CreateResourceVersionRequest initialVersion
    ) {
    }

    public record CreateResourceVersionRequest(
        String summary,
        String configDigest,
        VersionStatus status,
        ResourceVersionConfigurationDto configuration
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
        List<AssistantDto> assistants,
        List<AgentDto> agents,
        List<ResourceDto> resources,
        List<AssistantOrchestrationDto> orchestrations,
        ResourceCenterDto resourceCenter,
        List<ResourceBlueprintDto> resourceBlueprints
    ) {
    }
}
