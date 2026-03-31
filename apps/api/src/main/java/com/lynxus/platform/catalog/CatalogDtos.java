package com.lynxus.platform.catalog;

import com.lynxus.contracts.runtime.WorkflowContracts.OrchestrationNodeType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolProviderType;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record BusinessDomainDto(
        String id,
        String name,
        String description,
        List<ScenarioDto> scenarios,
        List<ResourceDto> resources,
        List<KnowledgeBaseDto> knowledgeBases
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
        KnowledgeBindingSnapshotDto assistantKnowledge,
        List<AssistantReleaseResourceDto> resources,
        List<AssistantReleaseAgentDto> agents,
        AssistantOrchestrationDto orchestration,
        AssistantModelPolicyDto modelPolicy,
        RagPolicyDto ragPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
    }

    public record AssistantReleaseResourceDto(
        String resourceId,
        String resourceName,
        ResourceType resourceType,
        String resourceVersionId,
        String resourceVersion,
        List<String> boundAgents,
        ResourceVersionConfigurationDto configuration
    ) {
    }

    public record AssistantReleaseAgentDto(
        String agentId,
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy,
        KnowledgeBindingSnapshotDto knowledge,
        List<String> skillResourceVersionIds,
        List<String> toolResourceVersionIds
    ) {
    }

    public record AgentDto(
        String id,
        String assistantId,
        String name,
        String role,
        String responsibility,
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

    public record ResourceVersionDto(
        String id,
        String resourceId,
        String version,
        VersionStatus status,
        String summary,
        Instant createdAt,
        Instant publishedAt,
        ResourceVersionConfigurationDto configuration
    ) {
    }

    public record StoredResourceVersion(
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
        ToolConfigDto tool,
        LlmModelConfigDto llmModel,
        SkillConfigDto skill
    ) {
    }

    public record KnowledgeRetrievalProfileDto(
        int defaultTopK,
        String retrievalMode,
        double minScore
    ) {
    }

    public record KnowledgeBindingSnapshotDto(
        String knowledgeBaseId,
        String knowledgeBaseName,
        String knowledgeReleaseId,
        String knowledgeReleaseVersion,
        String snapshotId,
        int defaultTopK,
        String retrievalMode,
        double minScore
    ) {
    }

    public record KnowledgeBaseDto(
        String id,
        String domainId,
        String name,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary,
        String steward,
        List<String> tags,
        KnowledgeReleaseDto latestRelease,
        KnowledgeReleaseDto effectiveRelease,
        List<KnowledgeReleaseDto> releases
    ) {
    }

    public record KnowledgeReleaseDto(
        String id,
        String knowledgeBaseId,
        String version,
        VersionStatus status,
        String summary,
        String snapshotId,
        KnowledgeRetrievalProfileDto retrievalProfile,
        Instant createdAt,
        Instant publishedAt
    ) {
    }

    public record KnowledgeUploadSessionDto(
        String id,
        String knowledgeBaseId,
        String status,
        List<String> acceptedTypes
    ) {
    }

    public record KnowledgeFileDto(
        String id,
        String knowledgeBaseId,
        String uploadSessionId,
        String sourceType,
        String sourceUri,
        String fileName,
        String contentType,
        int sizeBytes,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record KnowledgeImportJobDto(
        String id,
        String knowledgeBaseId,
        String fileId,
        String sourceType,
        String sourceUri,
        String fileName,
        String status,
        String stage,
        int progressPercent,
        int retryCount,
        boolean retryable,
        String failureReason,
        Instant startedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
    }

    public record KnowledgeDocumentDto(
        String id,
        String knowledgeBaseId,
        String fileId,
        String title,
        String sourceUri,
        String documentType,
        String status,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record KnowledgeIndexSnapshotDto(
        String id,
        String knowledgeBaseId,
        String retrievalBackend,
        String retrievalMode,
        String status,
        String stage,
        int progressPercent,
        int retryCount,
        boolean retryable,
        int documentCount,
        int chunkCount,
        String failureReason,
        Instant startedAt,
        Instant builtAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record CreateKnowledgeUploadSessionRequest(String knowledgeBaseId) {
    }

    public record CreateKnowledgeIndexSnapshotRequest(List<String> documentIds) {
    }

    public record CreateKnowledgeUrlImportRequest(
        String url,
        String title
    ) {
    }

    public record KnowledgeUploadCompletionDto(
        KnowledgeFileDto file,
        KnowledgeImportJobDto importJob
    ) {
    }

    public record KnowledgeRetrievalPreviewRequest(
        String snapshotId,
        String query,
        Integer topK,
        Double minScore,
        String retrievalMode
    ) {
    }

    public record KnowledgeRetrievalPreviewHitDto(
        String chunkId,
        String documentId,
        String documentTitle,
        String sourceUri,
        String snippet,
        double score,
        Integer pageNumber,
        String headingPath
    ) {
    }

    public record KnowledgeRetrievalPreviewResultDto(
        List<KnowledgeRetrievalPreviewHitDto> hits,
        boolean lowConfidence
    ) {
    }

    public record ToolOperationDto(
        String name,
        String description,
        String inputSchema,
        String outputSchema
    ) {
    }

    public record HttpToolProviderConfigDto(
        String endpoint,
        String method
    ) {
    }

    public record McpToolProviderConfigDto(
        String serverName,
        String transport,
        String connectionUri,
        String namespace,
        int heartbeatSeconds,
        Map<String, String> operationMappings
    ) {
    }

    public record ToolConfigDto(
        List<ToolOperationDto> operations,
        ToolProviderType providerType,
        String authType,
        int timeoutSeconds,
        String retryPolicy,
        HttpToolProviderConfigDto http,
        McpToolProviderConfigDto mcp
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

    public record SkillConfigDto(
        String skillName,
        String skillDesc,
        String skillPrompt
    ) {
    }

    public record AssistantModelPolicyDto(
        String providerResourceId
    ) {
    }

    public record RagPolicyDto(
        boolean enabled,
        String knowledgeBaseId
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
        String systemPrompt,
        boolean ragEnabled,
        boolean inheritAssistantKnowledge,
        String knowledgeBaseId,
        int memoryWindowSize,
        List<String> skillResourceIds,
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

    public record HumanNodeConfigDto(
        String title,
        String instruction,
        String expectedAction,
        String resumeRouteKey
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
        String nodeKey,
        String nodeName,
        OrchestrationNodeType nodeType,
        String description,
        String agentId,
        HumanNodeConfigDto humanNode
    ) {
    }

    public record OrchestrationEdgeDto(
        String edgeKey,
        String sourceNodeKey,
        String targetNodeKey,
        String routeKey,
        String label,
        boolean defaultEdge
    ) {
    }

    public record ResourceReferenceDto(
        String resourceId,
        String resourceName,
        ResourceType type,
        ShareScope shareScope,
        String ownerLabel,
        String latestVersion,
        String effectiveVersion,
        String referenceKind,
        String sourceType,
        String sourceId,
        String sourceName,
        String resourceVersionId,
        String resourceVersion,
        boolean blocksDeletion
    ) {
    }

    public record ObjectReferenceRelationDto(
        String relationKind,
        String relationRole,
        String relationMode,
        String impactLevel,
        String targetType,
        String targetId,
        String targetName,
        String releaseId,
        String releaseVersion,
        String resourceVersionId,
        String resourceVersion,
        String knowledgeReleaseId,
        String knowledgeReleaseVersion
    ) {
    }

    public record ObjectReferenceAnalysisDto(
        String objectType,
        String objectId,
        String objectName,
        List<ObjectReferenceRelationDto> relations
    ) {
    }

    public record DeletionCascadeItemDto(
        String action,
        String relationKind,
        String targetType,
        String targetId,
        String targetName,
        String description,
        String releaseVersion,
        String resourceVersion,
        String knowledgeReleaseVersion
    ) {
    }

    public record DeletionImpactPreviewDto(
        String objectType,
        String objectId,
        String objectName,
        boolean canDelete,
        List<ObjectReferenceRelationDto> blockers,
        List<ObjectReferenceRelationDto> advisories,
        List<DeletionCascadeItemDto> cascadeDeletes
    ) {
    }

    public record ResourceCenterDto(
        int totalResources,
        int domainSharedResources,
        int privateResources,
        List<ResourceReferenceDto> references
    ) {
    }

    public record KnowledgeReferenceDto(
        String knowledgeBaseId,
        String referenceKind,
        String sourceType,
        String sourceId,
        String sourceName,
        String knowledgeReleaseId,
        String knowledgeReleaseVersion,
        boolean blocksDeletion
    ) {
    }

    public record CreateDomainRequest(String name, String description) {
    }

    public record UpdateDomainRequest(String name, String description) {
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
        String responsibility,
        AgentExecutionPolicyDto executionPolicy
    ) {
    }

    public record UpdateAgentRequest(
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy
    ) {
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

    public record UpdateResourceRequest(
        String name,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary,
        String steward,
        List<String> tags
    ) {
    }

    public record CreateResourceVersionRequest(
        String summary,
        VersionStatus status,
        ResourceVersionConfigurationDto configuration
    ) {
    }

    public record UpdateResourceVersionRequest(
        String summary,
        VersionStatus status,
        ResourceVersionConfigurationDto configuration
    ) {
    }

    public record CreateKnowledgeBaseRequest(
        String domainId,
        String name,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary,
        String steward,
        List<String> tags
    ) {
    }

    public record UpdateKnowledgeBaseRequest(
        String name,
        ShareScope shareScope,
        String ownerType,
        String ownerId,
        String summary,
        String steward,
        List<String> tags
    ) {
    }

    public record CreateKnowledgeReleaseRequest(
        String summary,
        VersionStatus status,
        String snapshotId,
        KnowledgeRetrievalProfileDto retrievalProfile
    ) {
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
        List<KnowledgeBaseDto> knowledgeBases,
        List<AssistantOrchestrationDto> orchestrations,
        ResourceCenterDto resourceCenter,
        List<ResourceBlueprintDto> resourceBlueprints
    ) {
    }
}
