package com.lynxus.platform.catalog;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolProviderType;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.session.SessionContracts.PlaybookNodeType;
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
        List<PlaybookDto> playbooks,
        AssistantReleaseDto currentRelease,
        List<AssistantReleaseDto> releases,
        String primaryAgentId,
        AssistantOwnerPolicyDto ownerPolicy,
        AssistantSessionPolicyDto sessionPolicy,
        AssistantReplyPolicyDto replyPolicy,
        AssistantPlaybookPolicyDto playbookPolicy,
        AssistantModelPolicyDto modelPolicy,
        String privacyModelResourceId,
        boolean privacyMappingEnabled,
        KnowledgeAccessPolicyDto knowledgeAccessPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
        public AssistantDto(
            String id,
            String scenarioId,
            String name,
            String description,
            VersionDto version,
            List<AgentDto> agents,
            List<PlaybookDto> playbooks,
            AssistantReleaseDto currentRelease,
            List<AssistantReleaseDto> releases,
            String primaryAgentId,
            AssistantOwnerPolicyDto ownerPolicy,
            AssistantSessionPolicyDto sessionPolicy,
            AssistantReplyPolicyDto replyPolicy,
            AssistantPlaybookPolicyDto playbookPolicy,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                id,
                scenarioId,
                name,
                description,
                version,
                agents,
                playbooks,
                currentRelease,
                releases,
                primaryAgentId,
                ownerPolicy,
                sessionPolicy,
                replyPolicy,
                playbookPolicy,
                modelPolicy,
                null,
                false,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }
    }

    public record AssistantReleaseDto(
        String id,
        String assistantId,
        String releaseVersion,
        VersionStatus status,
        Instant createdAt,
        Instant publishedAt,
        KnowledgeBindingSnapshotDto assistantKnowledgeBinding,
        DefaultModelBindingDto defaultModelBinding,
        DefaultModelBindingDto privacyModelBinding,
        boolean privacyMappingEnabled,
        List<AssistantReleaseResourceDto> resources,
        List<AssistantReleaseAgentDto> agents,
        List<PlaybookDto> playbooks,
        String primaryAgentId,
        AssistantOwnerPolicyDto ownerPolicy,
        AssistantSessionPolicyDto sessionPolicy,
        AssistantReplyPolicyDto replyPolicy,
        AssistantPlaybookPolicyDto playbookPolicy,
        AssistantModelPolicyDto modelPolicy,
        KnowledgeAccessPolicyDto knowledgeAccessPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
        public AssistantReleaseDto(
            String id,
            String assistantId,
            String releaseVersion,
            VersionStatus status,
            Instant createdAt,
            Instant publishedAt,
            KnowledgeBindingSnapshotDto assistantKnowledgeBinding,
            DefaultModelBindingDto defaultModelBinding,
            List<AssistantReleaseResourceDto> resources,
            List<AssistantReleaseAgentDto> agents,
            List<PlaybookDto> playbooks,
            String primaryAgentId,
            AssistantOwnerPolicyDto ownerPolicy,
            AssistantSessionPolicyDto sessionPolicy,
            AssistantReplyPolicyDto replyPolicy,
            AssistantPlaybookPolicyDto playbookPolicy,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                id,
                assistantId,
                releaseVersion,
                status,
                createdAt,
                publishedAt,
                assistantKnowledgeBinding,
                defaultModelBinding,
                null,
                false,
                resources,
                agents,
                playbooks,
                primaryAgentId,
                ownerPolicy,
                sessionPolicy,
                replyPolicy,
                playbookPolicy,
                modelPolicy,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }
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

    public record DefaultModelBindingDto(
        String resourceId,
        String resourceName,
        String resourceVersionId,
        String resourceVersion,
        String providerType,
        String modelId
    ) {
    }

    public record AssistantReleaseAgentDto(
        String agentId,
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy,
        KnowledgeBindingSnapshotDto knowledgeBinding,
        DefaultModelBindingDto effectivePrivacyModelBinding,
        boolean effectivePrivacyMappingEnabled,
        boolean canOwnSession,
        List<AgentDecisionAction> allowedActions,
        List<String> switchableOwnerAgentIds,
        List<String> playbookIds,
        List<String> skillResourceVersionIds,
        List<String> toolResourceVersionIds
    ) {
        public AssistantReleaseAgentDto(
            String agentId,
            String name,
            String role,
            String responsibility,
            AgentExecutionPolicyDto executionPolicy,
            KnowledgeBindingSnapshotDto knowledgeBinding,
            boolean canOwnSession,
            List<AgentDecisionAction> allowedActions,
            List<String> switchableOwnerAgentIds,
            List<String> playbookIds,
            List<String> skillResourceVersionIds,
            List<String> toolResourceVersionIds
        ) {
            this(
                agentId,
                name,
                role,
                responsibility,
                executionPolicy,
                knowledgeBinding,
                null,
                false,
                canOwnSession,
                allowedActions,
                switchableOwnerAgentIds,
                playbookIds,
                skillResourceVersionIds,
                toolResourceVersionIds
            );
        }
    }

    public record AgentDto(
        String id,
        String assistantId,
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy,
        boolean canOwnSession,
        List<AgentDecisionAction> allowedActions,
        List<String> switchableOwnerAgentIds,
        List<String> playbookIds
    ) {
    }

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

    public record PlaybookDto(
        String id,
        String assistantId,
        String name,
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
        double temperature,
        int maxTokens,
        boolean privateDeployment
    ) {
        public LlmModelConfigDto(
            String providerType,
            String modelId,
            String baseUrl,
            String apiKeyEnvVar,
            double temperature,
            int maxTokens
        ) {
            this(providerType, modelId, baseUrl, apiKeyEnvVar, temperature, maxTokens, false);
        }
    }

    public record SkillConfigDto(
        String skillName,
        String skillDesc,
        String skillPrompt
    ) {
    }

    public record AssistantModelPolicyDto(
        String defaultModelResourceId
    ) {
    }

    public record AssistantOwnerPolicyDto(
        int maxOwnerSwitchesPerTurn
    ) {
    }

    public record AssistantSessionPolicyDto(
        String idleTimeout,
        String maxWorkflowAge,
        int maxWorkflowHistoryEvents
    ) {
    }

    public record AssistantReplyPolicyDto(
        boolean ownerOnly
    ) {
    }

    public record AssistantPlaybookPolicyDto(
        String timeoutPolicy,
        String retryPolicy
    ) {
    }

    public record KnowledgeAccessPolicyDto(
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
        String privacyModelResourceId,
        Boolean privacyMappingEnabled,
        String systemPrompt,
        boolean knowledgeEnabled,
        boolean inheritAssistantKnowledge,
        String knowledgeBaseId,
        int memoryWindowSize,
        List<String> skillResourceIds,
        List<String> toolResourceIds
    ) {
        public AgentExecutionPolicyDto(
            boolean inheritAssistantDefaults,
            String modelResourceId,
            String systemPrompt,
            boolean knowledgeEnabled,
            boolean inheritAssistantKnowledge,
            String knowledgeBaseId,
            int memoryWindowSize,
            List<String> skillResourceIds,
            List<String> toolResourceIds
        ) {
            this(
                inheritAssistantDefaults,
                modelResourceId,
                null,
                null,
                systemPrompt,
                knowledgeEnabled,
                inheritAssistantKnowledge,
                knowledgeBaseId,
                memoryWindowSize,
                skillResourceIds,
                toolResourceIds
            );
        }
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
        String primaryAgentId,
        AssistantOwnerPolicyDto ownerPolicy,
        AssistantSessionPolicyDto sessionPolicy,
        AssistantReplyPolicyDto replyPolicy,
        AssistantPlaybookPolicyDto playbookPolicy,
        AssistantModelPolicyDto modelPolicy,
        String privacyModelResourceId,
        boolean privacyMappingEnabled,
        KnowledgeAccessPolicyDto knowledgeAccessPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
        public CreateAssistantRequest(
            String scenarioId,
            String name,
            String description,
            String primaryAgentId,
            AssistantOwnerPolicyDto ownerPolicy,
            AssistantSessionPolicyDto sessionPolicy,
            AssistantReplyPolicyDto replyPolicy,
            AssistantPlaybookPolicyDto playbookPolicy,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                scenarioId,
                name,
                description,
                primaryAgentId,
                ownerPolicy,
                sessionPolicy,
                replyPolicy,
                playbookPolicy,
                modelPolicy,
                null,
                false,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }

        public CreateAssistantRequest(
            String scenarioId,
            String name,
            String description,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(scenarioId, name, description, modelPolicy, null, false, knowledgeAccessPolicy, memoryPolicy);
        }

        public CreateAssistantRequest(
            String scenarioId,
            String name,
            String description,
            AssistantModelPolicyDto modelPolicy,
            String privacyModelResourceId,
            boolean privacyMappingEnabled,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                scenarioId,
                name,
                description,
                null,
                null,
                null,
                null,
                null,
                modelPolicy,
                privacyModelResourceId,
                privacyMappingEnabled,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }
    }

    public record UpdateAssistantRequest(
        String name,
        String description,
        VersionStatus status,
        String primaryAgentId,
        AssistantOwnerPolicyDto ownerPolicy,
        AssistantSessionPolicyDto sessionPolicy,
        AssistantReplyPolicyDto replyPolicy,
        AssistantPlaybookPolicyDto playbookPolicy,
        AssistantModelPolicyDto modelPolicy,
        String privacyModelResourceId,
        boolean privacyMappingEnabled,
        KnowledgeAccessPolicyDto knowledgeAccessPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
        public UpdateAssistantRequest(
            String name,
            String description,
            VersionStatus status,
            String primaryAgentId,
            AssistantOwnerPolicyDto ownerPolicy,
            AssistantSessionPolicyDto sessionPolicy,
            AssistantReplyPolicyDto replyPolicy,
            AssistantPlaybookPolicyDto playbookPolicy,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                name,
                description,
                status,
                primaryAgentId,
                ownerPolicy,
                sessionPolicy,
                replyPolicy,
                playbookPolicy,
                modelPolicy,
                null,
                false,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }

        public UpdateAssistantRequest(
            String name,
            String description,
            VersionStatus status,
            AssistantModelPolicyDto modelPolicy,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(name, description, status, modelPolicy, null, false, knowledgeAccessPolicy, memoryPolicy);
        }

        public UpdateAssistantRequest(
            String name,
            String description,
            VersionStatus status,
            AssistantModelPolicyDto modelPolicy,
            String privacyModelResourceId,
            boolean privacyMappingEnabled,
            KnowledgeAccessPolicyDto knowledgeAccessPolicy,
            MemoryPolicyDto memoryPolicy
        ) {
            this(
                name,
                description,
                status,
                null,
                null,
                null,
                null,
                null,
                modelPolicy,
                privacyModelResourceId,
                privacyMappingEnabled,
                knowledgeAccessPolicy,
                memoryPolicy
            );
        }
    }

    public record CreateAgentRequest(
        String assistantId,
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy,
        boolean canOwnSession,
        List<AgentDecisionAction> allowedActions,
        List<String> switchableOwnerAgentIds,
        List<String> playbookIds
    ) {
        public CreateAgentRequest(
            String assistantId,
            String name,
            String role,
            String responsibility,
            AgentExecutionPolicyDto executionPolicy
        ) {
            this(
                assistantId,
                name,
                role,
                responsibility,
                executionPolicy,
                true,
                List.of(
                    AgentDecisionAction.REPLY,
                    AgentDecisionAction.NO_REPLY,
                    AgentDecisionAction.SWITCH_OWNER,
                    AgentDecisionAction.RUN_PLAYBOOK,
                    AgentDecisionAction.SESSION_HUMAN_HANDOFF
                ),
                List.of(),
                List.of()
            );
        }
    }

    public record UpdateAgentRequest(
        String name,
        String role,
        String responsibility,
        AgentExecutionPolicyDto executionPolicy,
        boolean canOwnSession,
        List<AgentDecisionAction> allowedActions,
        List<String> switchableOwnerAgentIds,
        List<String> playbookIds
    ) {
        public UpdateAgentRequest(
            String name,
            String role,
            String responsibility,
            AgentExecutionPolicyDto executionPolicy
        ) {
            this(
                name,
                role,
                responsibility,
                executionPolicy,
                true,
                List.of(
                    AgentDecisionAction.REPLY,
                    AgentDecisionAction.NO_REPLY,
                    AgentDecisionAction.SWITCH_OWNER,
                    AgentDecisionAction.RUN_PLAYBOOK,
                    AgentDecisionAction.SESSION_HUMAN_HANDOFF
                ),
                List.of(),
                List.of()
            );
        }
    }

    public record CreatePlaybookRequest(
        String assistantId,
        String name,
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

    public record UpdatePlaybookRequest(
        String name,
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

    public record CatalogSummaryDto(
        List<BusinessDomainDto> domains,
        List<ScenarioDto> scenarios,
        List<AssistantDto> assistants,
        List<AgentDto> agents,
        List<ResourceDto> resources,
        List<KnowledgeBaseDto> knowledgeBases,
        ResourceCenterDto resourceCenter,
        List<ResourceBlueprintDto> resourceBlueprints
    ) {
    }
}
