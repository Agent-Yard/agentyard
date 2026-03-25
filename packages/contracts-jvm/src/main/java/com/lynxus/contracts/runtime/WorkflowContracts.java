package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    public enum ResourceType {
        TOOL,
        KNOWLEDGE_BASE,
        LLM_MODEL,
        SKILL
    }

    public enum ToolProviderType {
        HTTP,
        MCP
    }

    public enum ShareScope {
        PRIVATE,
        DOMAIN_SHARED
    }

    public enum VersionStatus {
        DRAFT,
        PUBLISHED
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        WAITING_HUMAN,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum WorkflowStatus {
        DRAFT,
        RUNNING,
        WAITING_HUMAN,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum NodeStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        WAITING_HUMAN,
        CANCELLED
    }

    public enum OrchestrationNodeType {
        START,
        AGENT,
        HUMAN,
        END
    }

    public record KnowledgeBaseConfig(
        int defaultTopK,
        List<KnowledgeBaseDocument> documents
    ) {
    }

    public record KnowledgeBaseDocument(
        String id,
        String title,
        String content,
        String sourceUri
    ) {
    }

    public record ToolOperationConfig(
        String name,
        String description,
        String inputSchema,
        String outputSchema
    ) {
    }

    public record HttpToolProviderConfig(
        String endpoint,
        String method
    ) {
    }

    public record McpToolProviderConfig(
        String serverName,
        String transport,
        String connectionUri,
        String namespace,
        int heartbeatSeconds,
        Map<String, String> operationMappings
    ) {
    }

    public record ToolConfig(
        List<ToolOperationConfig> operations,
        ToolProviderType providerType,
        String authType,
        int timeoutSeconds,
        String retryPolicy,
        HttpToolProviderConfig http,
        McpToolProviderConfig mcp
    ) {
    }

    public record LlmModelConfig(
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

    public record SkillConfig(
        String skillName,
        String skillDesc,
        String skillPrompt
    ) {
    }

    public record ResourceConfigurationSnapshot(
        ResourceType type,
        KnowledgeBaseConfig knowledgeBase,
        ToolConfig tool,
        LlmModelConfig llmModel,
        SkillConfig skill
    ) {
    }

    public record ResourceVersionSnapshot(
        String resourceId,
        String resourceName,
        ResourceType resourceType,
        String resourceVersionId,
        String resourceVersion,
        List<String> boundAgents,
        ResourceConfigurationSnapshot configuration
    ) {
    }

    public record AssistantPolicySnapshot(
        String providerResourceId,
        String providerResourceVersionId,
        boolean ragEnabled,
        String knowledgeBaseResourceId,
        String knowledgeBaseResourceVersionId,
        boolean memoryEnabled,
        int memoryWindowSize
    ) {
    }

    public record AgentExecutionPolicySnapshot(
        boolean inheritAssistantDefaults,
        String modelResourceId,
        String modelResourceVersionId,
        String systemPrompt,
        boolean ragEnabled,
        String knowledgeBaseResourceId,
        String knowledgeBaseResourceVersionId,
        int memoryWindowSize,
        List<String> skillResourceIds,
        List<String> skillResourceVersionIds,
        List<String> toolResourceIds,
        List<String> toolResourceVersionIds
    ) {
    }

    public record AgentSnapshot(
        String agentId,
        String name,
        String role,
        String instructions,
        AgentExecutionPolicySnapshot executionPolicy
    ) {
    }

    public record HumanNodeConfig(
        String title,
        String instruction,
        String expectedAction,
        String resumeRouteKey
    ) {
    }

    public record GraphNodeSnapshot(
        String nodeKey,
        String nodeName,
        OrchestrationNodeType nodeType,
        String description,
        String agentId,
        HumanNodeConfig humanNode
    ) {
    }

    public record GraphEdgeSnapshot(
        String edgeKey,
        String sourceNodeKey,
        String targetNodeKey,
        String routeKey,
        String label,
        boolean defaultEdge
    ) {
    }

    public record GraphSnapshot(
        String executionMode,
        List<GraphNodeSnapshot> nodes,
        List<GraphEdgeSnapshot> edges
    ) {
    }

    public record AssistantRunSnapshot(
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        AssistantPolicySnapshot assistantPolicy,
        List<AgentSnapshot> agents,
        List<ResourceVersionSnapshot> resources,
        GraphSnapshot graph
    ) {
    }

    public record SessionMessageSnapshot(
        String role,
        String senderName,
        String content,
        Instant createdAt
    ) {
    }

    public record SessionContext(
        String sessionId,
        String requester,
        String latestMessage,
        List<SessionMessageSnapshot> history,
        List<String> loadedSkillResourceVersionIds
    ) {
    }

    public record WorkflowStartRequest(
        String taskId,
        String workflowInstanceId,
        String scenarioId,
        String question,
        String operatorId,
        SessionContext sessionContext,
        AssistantRunSnapshot assistant
    ) {
    }

    public record HumanAction(
        String action,
        String comment,
        String operatorId,
        Map<String, String> attributes
    ) {
    }

    public record ExecutionCheckpoint(
        String checkpointId,
        String currentNodeKey,
        String waitingNodeKey,
        String statePayload,
        int resumeCount
    ) {
    }

    public record WorkflowResumeRequest(
        String taskId,
        String workflowInstanceId,
        String scenarioId,
        HumanAction action,
        SessionContext sessionContext,
        AssistantRunSnapshot assistant,
        ExecutionCheckpoint checkpoint
    ) {
    }

    public record ToolInvocationSnapshot(
        String id,
        String providerType,
        String resourceId,
        String resourceName,
        String operation,
        String status,
        String detail,
        Instant createdAt
    ) {
    }

    public record HumanTaskSnapshot(
        String nodeKey,
        String title,
        String instruction,
        String expectedAction,
        String source,
        List<String> allowedActions
    ) {
    }

    public record PauseReasonSnapshot(
        String code,
        String detail,
        String source
    ) {
    }

    public record ToolOutcomeSummary(
        String toolResourceId,
        String toolResourceName,
        String operation,
        String providerType,
        String status,
        String externalReference,
        String recommendedAction,
        String detail
    ) {
    }

    public record NodeSnapshot(
        String nodeKey,
        String nodeName,
        NodeStatus status,
        String detail,
        Instant updatedAt
    ) {
    }

    public record WorkflowResult(
        String workflowInstanceId,
        WorkflowStatus status,
        String summary,
        String finalReply,
        String currentNodeKey,
        ExecutionCheckpoint checkpoint,
        HumanTaskSnapshot humanTask,
        PauseReasonSnapshot pauseReason,
        List<NodeSnapshot> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        boolean escalationRequired,
        ToolOutcomeSummary latestToolOutcome,
        List<String> loadedSkillResourceVersionIds
    ) {
    }
}
