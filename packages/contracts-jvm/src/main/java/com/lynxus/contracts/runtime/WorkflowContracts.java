package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    public enum ResourceType {
        SKILL,
        MCP,
        KNOWLEDGE_BASE,
        LLM_MODEL,
        PROMPT_TEMPLATE
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
        WAITING_HUMAN
    }

    public enum OrchestrationNodeType {
        START,
        AGENT,
        HUMAN,
        END
    }

    public record KnowledgeBaseConfig(
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

    public record SkillConfig(
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

    public record McpConfig(
        String serverName,
        String transport,
        String connectionUri,
        String namespace,
        String authType,
        int heartbeatSeconds,
        List<String> exposedTools
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

    public record PromptTemplateConfig(
        String templateType,
        String systemPrompt,
        String userPromptTemplate,
        String responseFormat
    ) {
    }

    public record ResourceConfigurationSnapshot(
        ResourceType type,
        KnowledgeBaseConfig knowledgeBase,
        SkillConfig skill,
        McpConfig mcp,
        LlmModelConfig llmModel,
        PromptTemplateConfig promptTemplate
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
        String promptTemplateResourceId,
        double temperature,
        int maxTokens,
        boolean ragEnabled,
        String knowledgeBaseResourceId,
        int ragTopK,
        boolean memoryEnabled,
        int memoryWindowSize
    ) {
    }

    public record AgentExecutionPolicySnapshot(
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

    public record AgentSnapshot(
        String agentId,
        String name,
        String role,
        String instructions,
        AgentExecutionPolicySnapshot executionPolicy,
        List<String> bindingResourceVersionIds
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
        List<SessionMessageSnapshot> history
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
        String toolType,
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
        String expectedAction
    ) {
    }

    public record McpInvocationSummary(
        String capabilityName,
        String externalTicketId,
        String status,
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
        List<NodeSnapshot> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        boolean escalationRequired,
        McpInvocationSummary mcpSummary
    ) {
    }
}
