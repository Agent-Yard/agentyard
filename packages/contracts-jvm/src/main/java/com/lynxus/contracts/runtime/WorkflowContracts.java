package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Map<String, Object>> immutableAgentScopes(Map<String, Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Object>> copy = new LinkedHashMap<>();
        source.forEach((agentId, scope) -> copy.put(agentId, immutableObjectMap(scope)));
        return Collections.unmodifiableMap(copy);
    }

    public enum ResourceType {
        TOOL,
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

    public enum DecisionType {
        FINAL,
        TOOL_CALL,
        SKILL_READ,
        HUMAN_HANDOFF
    }

    public enum SessionStatePatchTarget {
        FACTS,
        ARTIFACTS,
        AGENT_SCOPE
    }

    public enum SessionStatePatchOpType {
        UPSERT,
        REMOVE
    }

    public record KnowledgeBindingSnapshot(
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
        boolean inheritAssistantKnowledge,
        KnowledgeBindingSnapshot knowledge,
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
        String responsibility,
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
        KnowledgeBindingSnapshot assistantKnowledge,
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

    public record SharedSessionState(
        Map<String, Object> facts,
        Map<String, Object> artifacts,
        Map<String, Map<String, Object>> agentScopes
    ) {
        public SharedSessionState {
            facts = immutableObjectMap(facts);
            artifacts = immutableObjectMap(artifacts);
            agentScopes = immutableAgentScopes(agentScopes);
        }

        public static SharedSessionState empty() {
            return new SharedSessionState(Map.of(), Map.of(), Map.of());
        }
    }

    public record ToolRequest(
        String toolResourceVersionId,
        String operation,
        Map<String, Object> arguments
    ) {
        public ToolRequest {
            arguments = immutableObjectMap(arguments);
        }
    }

    public record HumanRequest(
        String title,
        String instruction,
        String expectedAction
    ) {
    }

    public record SessionStatePatchOp(
        SessionStatePatchTarget target,
        SessionStatePatchOpType op,
        List<String> path,
        Object value
    ) {
        public SessionStatePatchOp {
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    public record SessionStatePatch(
        List<SessionStatePatchOp> ops
    ) {
        public SessionStatePatch {
            ops = ops == null ? List.of() : List.copyOf(ops);
        }
    }

    public record StructuredAgentDecision(
        DecisionType decisionType,
        String message,
        String routeDecision,
        List<String> skillReads,
        List<ToolRequest> toolRequests,
        HumanRequest humanRequest,
        SessionStatePatch sessionStatePatch
    ) {
        public StructuredAgentDecision {
            skillReads = skillReads == null ? List.of() : List.copyOf(skillReads);
            toolRequests = toolRequests == null ? List.of() : List.copyOf(toolRequests);
        }
    }

    public record AgentTurnLog(
        int turnIndex,
        String phase,
        DecisionType decisionType,
        int loadedSkillsDelta,
        int sessionStateOpsDelta,
        int toolCallsDelta,
        String routeSource,
        String failureReason
    ) {
    }

    public record AgentTurnState(
        String phase,
        int turnIndex,
        StructuredAgentDecision latestDecision,
        List<AgentTurnLog> turnLogs
    ) {
        public AgentTurnState {
            turnLogs = turnLogs == null ? List.of() : List.copyOf(turnLogs);
        }

        public static AgentTurnState empty() {
            return new AgentTurnState("IDLE", 0, null, List.of());
        }
    }

    public record SessionContext(
        String sessionId,
        String requester,
        String latestMessage,
        List<SessionMessageSnapshot> history,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
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
        Map<String, Object> result
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
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState,
        AgentTurnState agentTurnState
    ) {
    }

    public record KnowledgeImportRequest(
        String workflowId,
        String knowledgeBaseId,
        String importJobId
    ) {
    }

    public record KnowledgeIndexBuildRequest(
        String workflowId,
        String knowledgeBaseId,
        String indexSnapshotId
    ) {
    }

    public record KnowledgeJobResult(
        String workflowId,
        String knowledgeBaseId,
        String targetId,
        String status,
        String detail,
        Instant updatedAt
    ) {
    }
}
