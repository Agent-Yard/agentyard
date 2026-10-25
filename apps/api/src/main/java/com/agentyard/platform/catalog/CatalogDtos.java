package com.agentyard.platform.catalog;

import com.agentyard.contracts.runtime.WorkflowContracts.ResourceType;
import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record RoleTemplateDto(
        String id, String name, String description, String ownerType, String ownerId,
        RoleTemplateVersionDto latestVersion, RoleTemplateVersionDto effectiveVersion,
        List<RoleTemplateVersionDto> versions
    ) {}

    public record RoleConfigurationDto(
        String role, String responsibility, String systemPrompt, String modelVersionId,
        String privacyModelVersionId, boolean privacyMappingEnabled, String knowledgeReleaseId,
        int memoryWindowSize, List<String> skillVersionIds, List<String> toolVersionIds,
        List<String> playbookVersionIds
    ) {}

    public record RoleTemplateVersionDto(
        String id, String templateId, String version, VersionStatus status,
        Instant createdAt, Instant publishedAt, RoleConfigurationDto configuration
    ) {}

    public record CreateRoleTemplateRequest(
        String name, String description, String ownerType, String ownerId,
        CreateRoleTemplateVersionRequest initialVersion
    ) {}

    public record UpdateRoleTemplateRequest(String name, String description, String ownerType, String ownerId) {}
    public record CreateRoleTemplateVersionRequest(VersionStatus status, RoleConfigurationDto configuration) {}

    /** A reception entry routes people to a template; it is never a deployment bundle. */
    public record EntryDto(String id, String name, String description, String ownerType, String ownerId,
        ShareScope shareScope, String receptionTemplateVersionId, boolean enabled,
        List<String> allowedTemplateIds, List<com.agentyard.contracts.agent.AgentContracts.GrantRequest> capabilityGrants) {}
    public record EntryRequest(String name, String description, String ownerType, String ownerId,
        ShareScope shareScope, String receptionTemplateVersionId, boolean enabled,
        List<String> allowedTemplateIds, List<com.agentyard.contracts.agent.AgentContracts.GrantRequest> capabilityGrants) {}

    public record PlaybookDto(
        String id, String name, String description, String ownerType, String ownerId,
        PlaybookVersionDto latestVersion, PlaybookVersionDto effectiveVersion, List<PlaybookVersionDto> versions
    ) {}

    public record PlaybookVersionDto(
        String id, String playbookId, String version, VersionStatus status,
        Instant createdAt, Instant publishedAt, PlaybookDefinitionDto definition
    ) {}

    public record CreatePlaybookRequest(
        String name, String description, String ownerType, String ownerId,
        CreatePlaybookVersionRequest initialVersion
    ) {}
    public record UpdatePlaybookRequest(String name, String description, String ownerType, String ownerId) {}
    public record CreatePlaybookVersionRequest(VersionStatus status, PlaybookDefinitionDto definition) {}

    public record PlaybookExecutionPolicyDto(
        String timeoutPolicy,
        String retryPolicy
    ) {
    }

    public record PlaybookNodeLayoutDto(
        int x,
        int y
    ) {
    }

    public record PlaybookNodeDto(
        String nodeKey,
        String nodeName,
        PlaybookNodeType nodeType,
        String description,
        String scriptRef,
        String scriptVersion,
        String toolId,
        String toolOperation,
        Map<String, Object> config,
        PlaybookNodeLayoutDto layout
    ) {
    }

    public record PlaybookEdgeDto(
        String edgeKey,
        String sourceNodeKey,
        String targetNodeKey,
        String routeKey,
        String label,
        boolean defaultEdge
    ) {
    }

    public record PlaybookDefinitionDto(
        String description,
        String inputSchema,
        String resultSchema,
        PlaybookExecutionPolicyDto executionPolicy,
        boolean allowHumanTask,
        boolean allowExternalInteraction,
        String entryNodeKey,
        List<PlaybookNodeDto> nodes,
        List<PlaybookEdgeDto> edges
    ) {
    }

    public record ResourceDto(
        String id,
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

    public record KnowledgeDocumentDeletionBlockerDto(
        String snapshotId,
        String status,
        String stage,
        String retrievalMode,
        String reason
    ) {
    }

    public record KnowledgeDocumentDeletionPreviewDto(
        String documentId,
        String knowledgeBaseId,
        String fileId,
        String fileName,
        String sourceUri,
        String title,
        int chunkCount,
        boolean canDelete,
        List<KnowledgeDocumentDeletionBlockerDto> blockers
    ) {
    }

    public record KnowledgeDocumentDeletionResultDto(
        String documentId,
        String knowledgeBaseId,
        String fileId,
        String fileName,
        String title,
        int deletedChunkCount,
        int deletedImportJobCount,
        int deletedDocumentCount,
        boolean deletedStorageObject
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

    public record ToolConnectorConfigDto(
        String connectorType,
        String accountId,
        ToolConnectorAccountSnapshotDto accountSnapshot,
        int timeoutSeconds,
        String retryPolicy,
        Map<String, Object> config,
        Map<String, Map<String, Object>> operationMappings
    ) {
    }

    public record ToolConnectorAccountSnapshotDto(
        String accountId,
        boolean hasExternalSecretRef,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String runtimeSecretRef
    ) {
        public ToolConnectorAccountSnapshotDto(String accountId, String runtimeSecretRef) {
            this(accountId, runtimeSecretRef != null && !runtimeSecretRef.isBlank(), runtimeSecretRef);
        }
    }

    public record ToolConfigDto(
        List<ToolOperationDto> operations,
        ToolConnectorConfigDto connector
    ) {
    }

    public record LlmModelConfigDto(
        String providerType,
        String modelId,
        String baseUrl,
        String apiKeyEnvVar,
        double temperature,
        int maxTokens,
        boolean privateDeployment,
        Boolean enableThinking,
        String reasoningEffort
    ) {
        public LlmModelConfigDto(
            String providerType,
            String modelId,
            String baseUrl,
            String apiKeyEnvVar,
            double temperature,
            int maxTokens,
            boolean privateDeployment
        ) {
            this(providerType, modelId, baseUrl, apiKeyEnvVar, temperature, maxTokens, privateDeployment, null, null);
        }

        public LlmModelConfigDto(
            String providerType,
            String modelId,
            String baseUrl,
            String apiKeyEnvVar,
            double temperature,
            int maxTokens
        ) {
            this(providerType, modelId, baseUrl, apiKeyEnvVar, temperature, maxTokens, false, null, null);
        }
    }

    public record SkillConfigDto(
        String skillName,
        String skillDesc,
        String skillPrompt
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
        int sharedResources,
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

    public record CreateResourceRequest(
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

    public record CatalogSummaryDto(
        List<RoleTemplateDto> roleTemplates, List<EntryDto> entries, List<PlaybookDto> playbooks,
        List<ResourceDto> resources, List<KnowledgeBaseDto> knowledgeBases,
        ResourceCenterDto resourceCenter, List<ResourceBlueprintDto> resourceBlueprints
    ) {}
}
