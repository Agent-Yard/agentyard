package com.lynxus.contracts.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SessionContracts {
    private SessionContracts() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Integer> immutableIntegerMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum SessionMessageDeliveryStatus {
        ACCEPTED,
        BUSY,
        REJECTED
    }

    public enum AgentDecisionAction {
        REPLY,
        NO_REPLY,
        SWITCH_OWNER,
        RUN_PLAYBOOK,
        SESSION_HUMAN_HANDOFF
    }

    public enum SessionTriggerType {
        USER_MESSAGE,
        PLAYBOOK_COMPLETED
    }

    public enum SessionActorType {
        USER,
        AGENT,
        SYSTEM,
        HUMAN_OPERATOR,
        EXTERNAL_SYSTEM
    }

    public enum SessionEventType {
        USER_MESSAGE,
        OWNER_REPLY,
        HUMAN_OPERATOR_REPLY,
        AGENT_DECISION_REJECTED,
        AGENT_TURN_FAILED,
        OWNER_SWITCH,
        PLAYBOOK_STARTED,
        PLAYBOOK_WAITING,
        PLAYBOOK_RESUMED,
        PLAYBOOK_COMPLETED,
        SESSION_HUMAN_HANDOFF_STARTED,
        SESSION_HUMAN_HANDOFF_ENDED,
        HUMAN_RESUME_RECEIVED,
        EXTERNAL_CALLBACK_RECEIVED
    }

    public enum PlaybookRunStatus {
        RUNNING,
        WAITING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public enum PlaybookNodeType {
        STEP,
        TOOL_TASK,
        HUMAN_TASK,
        EXTERNAL_INTERACTION,
        END
    }

    public enum PlaybookWaitingType {
        HUMAN_TASK,
        EXTERNAL_INTERACTION
    }

    public enum PlaybookResumeSource {
        HUMAN,
        EXTERNAL_SYSTEM
    }

    public enum PlaybookProgressType {
        WAITING,
        RESUMED
    }

    public record SessionOwnerPolicy(
        int maxOwnerSwitchesPerTurn
    ) {
    }

    public record SessionPolicy(
        Duration idleTimeout,
        Duration maxWorkflowAge,
        int maxWorkflowHistoryEvents
    ) {
    }

    public record PlaybookExecutionPolicy(
        String timeoutPolicy,
        String retryPolicy
    ) {
    }

    public record AssistantSessionConfig(
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        String primaryAgentId,
        SessionOwnerPolicy ownerPolicy,
        SessionPolicy sessionPolicy,
        PlaybookExecutionPolicy playbookPolicy
    ) {
    }

    public record LlmModelDescriptor(
        String resourceId,
        String resourceName,
        String resourceVersionId,
        String resourceVersion,
        String providerType,
        String modelId,
        String baseUrl,
        String apiKeyEnvVar,
        double temperature,
        int maxTokens,
        boolean privateDeployment
    ) {
    }

    public record KnowledgeBindingDescriptor(
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

    public record SkillDescriptor(
        String resourceId,
        String resourceName,
        String resourceVersionId,
        String resourceVersion,
        String skillName,
        String skillDesc,
        String skillPrompt
    ) {
    }

    public record ToolOperationDescriptor(
        String name,
        String description,
        String inputSchema,
        String outputSchema
    ) {
    }

    public record HttpToolProviderDescriptor(
        String endpoint,
        String method
    ) {
    }

    public record McpToolProviderDescriptor(
        String serverName,
        String transport,
        String connectionUri,
        String namespace,
        int heartbeatSeconds,
        Map<String, String> operationMappings
    ) {
        public McpToolProviderDescriptor {
            operationMappings = immutableStringMap(operationMappings);
        }
    }

    public record ToolDescriptor(
        String resourceId,
        String resourceName,
        String resourceVersionId,
        String resourceVersion,
        List<ToolOperationDescriptor> operations,
        String providerType,
        String authType,
        int timeoutSeconds,
        String retryPolicy,
        HttpToolProviderDescriptor http,
        McpToolProviderDescriptor mcp
    ) {
        public ToolDescriptor {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    public record AgentConfig(
        String agentId,
        String name,
        String role,
        String responsibility,
        LlmModelDescriptor model,
        LlmModelDescriptor effectivePrivacyModelBinding,
        boolean effectivePrivacyMappingEnabled,
        String systemPrompt,
        boolean knowledgeEnabled,
        String knowledgeBaseId,
        KnowledgeBindingDescriptor knowledgeBinding,
        int memoryWindowSize,
        boolean canOwnSession,
        List<AgentDecisionAction> allowedActions,
        List<String> switchableOwnerAgentIds,
        List<String> playbookIds,
        List<SkillDescriptor> skills,
        List<ToolDescriptor> tools
    ) {
        public AgentConfig {
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
            switchableOwnerAgentIds = switchableOwnerAgentIds == null ? List.of() : List.copyOf(switchableOwnerAgentIds);
            playbookIds = playbookIds == null ? List.of() : List.copyOf(playbookIds);
            skills = skills == null ? List.of() : List.copyOf(skills);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record PlaybookNode(
        String nodeKey,
        String nodeName,
        PlaybookNodeType nodeType,
        String description,
        String scriptRef,
        String scriptVersion,
        String toolId,
        String toolOperation,
        Map<String, Object> config,
        PlaybookNodeLayout layout
    ) {
        public PlaybookNode {
            config = immutableObjectMap(config);
        }
    }

    public record PlaybookNodeLayout(
        int x,
        int y
    ) {
    }

    public record PlaybookEdge(
        String edgeKey,
        String sourceNodeKey,
        String targetNodeKey,
        String routeKey,
        String label,
        boolean defaultEdge
    ) {
    }

    public record PlaybookConfig(
        String playbookId,
        String name,
        String description,
        String inputSchema,
        String resultSchema,
        PlaybookExecutionPolicy executionPolicy,
        boolean allowHumanTask,
        boolean allowExternalInteraction,
        String entryNodeKey,
        List<PlaybookNode> nodes,
        List<PlaybookEdge> edges
    ) {
        public PlaybookConfig {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    public record SessionEvent(
        String eventId,
        String sessionId,
        long sequence,
        SessionEventType eventType,
        Instant createdAt,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        public SessionEvent {
            payload = immutableObjectMap(payload);
        }
    }

    public record PlaybookRun(
        String runId,
        String sessionId,
        String parentSessionEventId,
        String playbookId,
        String ownerAgentId,
        PlaybookRunStatus status,
        Map<String, Object> input,
        Map<String, Object> result,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        String waitingReason
    ) {
        public PlaybookRun {
            input = immutableObjectMap(input);
            result = immutableObjectMap(result);
        }
    }

    public record ActivePlaybookSummary(
        String runId,
        String playbookId,
        String playbookName,
        PlaybookRunStatus status,
        String waitingReason,
        Map<String, Object> latestResult
    ) {
        public ActivePlaybookSummary {
            latestResult = immutableObjectMap(latestResult);
        }
    }

    public record SessionTrigger(
        SessionTriggerType triggerType,
        String eventId,
        Map<String, Object> payload
    ) {
        public SessionTrigger {
            payload = immutableObjectMap(payload);
        }
    }

    public record PrivacyMappingTelemetry(
        boolean enabled,
        String privacyModelResourceId,
        String privacyModelResourceName,
        Map<String, Integer> sanitizeCountByChannel,
        Map<String, Integer> restoreCountByChannel,
        Map<String, Integer> entityTypeBreakdown,
        int placeholderCount,
        int unresolvedPlaceholderCount,
        int blockedEventCount,
        Instant lastProcessedAt
    ) {
        public PrivacyMappingTelemetry {
            sanitizeCountByChannel = immutableIntegerMap(sanitizeCountByChannel);
            restoreCountByChannel = immutableIntegerMap(restoreCountByChannel);
            entityTypeBreakdown = immutableIntegerMap(entityTypeBreakdown);
        }
    }

    public record AgentDecision(
        AgentDecisionAction action,
        String replyContent,
        String targetAgentId,
        String playbookId,
        Map<String, Object> playbookInput,
        String accompanyingReply
    ) {
        public AgentDecision {
            playbookInput = immutableObjectMap(playbookInput);
        }
    }

    public record AgentTurnRequest(
        String sessionId,
        String assistantId,
        String assistantReleaseVersion,
        AgentConfig currentOwner,
        List<AgentConfig> availableAgents,
        List<PlaybookConfig> availablePlaybooks,
        ActivePlaybookSummary activePlaybook,
        Map<String, Object> sharedState,
        LlmModelDescriptor effectivePrivacyModelBinding,
        boolean effectivePrivacyMappingEnabled,
        SessionTrigger trigger,
        List<SessionEvent> recentEvents
    ) {
        public AgentTurnRequest {
            availableAgents = availableAgents == null ? List.of() : List.copyOf(availableAgents);
            availablePlaybooks = availablePlaybooks == null ? List.of() : List.copyOf(availablePlaybooks);
            sharedState = immutableObjectMap(sharedState);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        }
    }

    public record AgentTurnResult(
        AgentDecision decision,
        Map<String, Object> sharedState,
        PrivacyMappingTelemetry mappingTelemetry
    ) {
        public AgentTurnResult {
            sharedState = immutableObjectMap(sharedState);
        }
    }

    public record PlaybookToolTaskRequest(
        String sessionId,
        String playbookRunId,
        String playbookId,
        String nodeKey,
        String nodeName,
        AgentConfig ownerAgent,
        String toolId,
        String toolOperation,
        Map<String, Object> input,
        Map<String, Object> config
    ) {
        public PlaybookToolTaskRequest {
            input = immutableObjectMap(input);
            config = immutableObjectMap(config);
        }
    }

    public record PlaybookToolTaskResult(
        Map<String, Object> statePatch,
        String routeKey,
        PlaybookRunStatus terminalStatus,
        String failureReason
    ) {
        public PlaybookToolTaskResult {
            statePatch = immutableObjectMap(statePatch);
        }
    }

    public record UserMessage(
        String messageId,
        String customerId,
        String content,
        Map<String, Object> payload
    ) {
        public UserMessage {
            payload = immutableObjectMap(payload);
        }
    }

    public record SessionUserMessageUpdateResult(
        SessionMessageDeliveryStatus status,
        String sessionId,
        String reason
    ) {
    }

    public record SessionStartRequest(
        String sessionId,
        String scenarioId,
        String sessionTitle,
        String customerId,
        AssistantSessionConfig assistant,
        List<AgentConfig> agents,
        List<PlaybookConfig> playbooks,
        Map<String, Object> initialSharedState
    ) {
        public SessionStartRequest {
            agents = agents == null ? List.of() : List.copyOf(agents);
            playbooks = playbooks == null ? List.of() : List.copyOf(playbooks);
            initialSharedState = immutableObjectMap(initialSharedState);
        }
    }

    public record HumanResumeSignal(
        String sessionId,
        String playbookRunId,
        Map<String, Object> payload
    ) {
        public HumanResumeSignal {
            payload = immutableObjectMap(payload);
        }
    }

    public record ExternalCallbackSignal(
        String sessionId,
        String playbookRunId,
        Map<String, Object> payload
    ) {
        public ExternalCallbackSignal {
            payload = immutableObjectMap(payload);
        }
    }

    public record HumanOperatorReplySignal(
        String sessionId,
        String operatorId,
        String content,
        Map<String, Object> payload
    ) {
        public HumanOperatorReplySignal {
            payload = immutableObjectMap(payload);
        }
    }

    public record SessionSnapshot(
        String sessionId,
        String assistantId,
        String assistantReleaseVersion,
        String primaryAgentId,
        String currentOwnerAgentId,
        int ownerSwitchCountInTurn,
        Map<String, Object> sharedState,
        String activePlaybookRunId,
        boolean agentTurnActive,
        boolean sessionHumanHandoffActive,
        boolean pendingOwnerReevaluation,
        boolean draining,
        Instant idleDeadline
    ) {
        public SessionSnapshot {
            sharedState = immutableObjectMap(sharedState);
        }
    }

    public record PlaybookStartRequest(
        String sessionId,
        String playbookRunId,
        String triggeringEventId,
        String ownerAgentId,
        AgentConfig ownerAgent,
        PlaybookConfig playbook,
        Map<String, Object> input
    ) {
        public PlaybookStartRequest {
            input = immutableObjectMap(input);
        }
    }

    public record PlaybookResumeSignal(
        String playbookRunId,
        PlaybookResumeSource source,
        Map<String, Object> payload
    ) {
        public PlaybookResumeSignal {
            payload = immutableObjectMap(payload);
        }
    }

    public record PlaybookProgressUpdate(
        PlaybookProgressType progressType,
        PlaybookRun run,
        String nodeKey,
        PlaybookWaitingType waitingType,
        PlaybookResumeSource resumeSource,
        Map<String, Object> payload
    ) {
        public PlaybookProgressUpdate {
            payload = immutableObjectMap(payload);
        }
    }
}
