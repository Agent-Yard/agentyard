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

    public enum SessionMessageRole {
        USER,
        ASSISTANT,
        HUMAN_OPERATOR,
        SYSTEM
    }

    public enum SessionMessageStatus {
        SENT,
        STREAMING,
        DELIVERED,
        FAILED
    }

    public enum SessionMessageSenderType {
        CUSTOMER,
        AGENT,
        HUMAN_OPERATOR,
        SYSTEM
    }

    public enum SessionMessageBlockType {
        TEXT,
        IMAGE,
        RICH_TEXT,
        CARD
    }

    public enum RichTextFormat {
        MARKDOWN
    }

    public enum CardActionType {
        LINK
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

    public enum LlmUsageSourceType {
        SESSION_OWNER_MODEL,
        SESSION_PRIVACY_MODEL
    }

    public enum SessionActorType {
        CUSTOMER,
        AGENT,
        SYSTEM,
        HUMAN_OPERATOR,
        EXTERNAL_SYSTEM
    }

    public enum SessionEventType {
        AGENT_DECISION_REJECTED,
        AGENT_TURN_FAILED,
        USER_MESSAGE_SECURITY_BLOCKED,
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

    public enum ToolConnectorRetryMode {
        NONE,
        FIXED,
        EXPONENTIAL
    }

    public record ToolConnectorRuntimeRetryPolicy(
        ToolConnectorRetryMode mode,
        int maxAttempts,
        int initialDelayMs,
        int maxDelayMs,
        double backoffMultiplier,
        List<String> retryableCategories,
        List<String> retryableErrorCodes
    ) {
        public ToolConnectorRuntimeRetryPolicy {
            retryableCategories = retryableCategories == null ? List.of() : List.copyOf(retryableCategories);
            retryableErrorCodes = retryableErrorCodes == null ? List.of() : List.copyOf(retryableErrorCodes);
        }
    }

    public record ToolConnectorDescriptor(
        String connectorType,
        ToolConnectorAccountSnapshot accountSnapshot,
        int timeoutSeconds,
        ToolConnectorRuntimeRetryPolicy retryPolicy,
        Map<String, Object> config,
        Map<String, Map<String, Object>> operationMappings
    ) {
        public ToolConnectorDescriptor {
            config = immutableObjectMap(config);
            if (operationMappings == null || operationMappings.isEmpty()) {
                operationMappings = Map.of();
            } else {
                Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
                operationMappings.forEach((key, value) -> copy.put(key, immutableObjectMap(value)));
                operationMappings = Collections.unmodifiableMap(copy);
            }
        }
    }

    public record ToolConnectorAccountSnapshot(
        String accountId,
        String externalSecretRef
    ) {
    }

    public record ToolDescriptor(
        String resourceId,
        String resourceName,
        String resourceVersionId,
        String resourceVersion,
        List<ToolOperationDescriptor> operations,
        ToolConnectorDescriptor connector
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

    public record SessionMessageSender(
        SessionMessageSenderType senderType,
        String senderId,
        String senderName
    ) {
    }

    public record TextMessageBlock(
        SessionMessageBlockType type,
        String text
    ) {
    }

    public record ImageMessageBlock(
        SessionMessageBlockType type,
        String url,
        String mimeType,
        Integer width,
        Integer height,
        String alt
    ) {
    }

    public record RichTextMessageBlock(
        SessionMessageBlockType type,
        RichTextFormat format,
        String content
    ) {
    }

    public record CardLinkAction(
        CardActionType actionType,
        String label,
        String url
    ) {
    }

    public record CardMessageBlock(
        SessionMessageBlockType type,
        String cardType,
        String version,
        Map<String, Object> data,
        List<CardLinkAction> actions
    ) {
        public CardMessageBlock {
            data = immutableObjectMap(data);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record SessionMessageInput(
        List<Object> blocks,
        Map<String, Object> metadata
    ) {
        public SessionMessageInput {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record SessionMessage(
        String messageId,
        String sessionId,
        long sequence,
        SessionMessageRole role,
        SessionMessageSender sender,
        SessionMessageStatus status,
        List<Object> blocks,
        Map<String, Object> metadata,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId,
        String sourceEventId,
        Instant createdAt,
        Instant updatedAt
    ) {
        public SessionMessage {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = immutableObjectMap(metadata);
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
        String relatedMessageId,
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
        String triggerMessageId,
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

    public record LlmUsageEntry(
        LlmUsageSourceType sourceType,
        int callSequence,
        int toolLoopStep,
        String providerType,
        String modelResourceId,
        String modelResourceVersionId,
        String modelId,
        boolean usageAvailable,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Map<String, Object> rawUsage,
        Instant occurredAt
    ) {
        public LlmUsageEntry {
            rawUsage = immutableObjectMap(rawUsage);
        }
    }

    public record AgentDecision(
        AgentDecisionAction action,
        SessionMessageInput replyMessage,
        String targetAgentId,
        String playbookId,
        Map<String, Object> playbookInput,
        SessionMessageInput accompanyingMessage
    ) {
        public AgentDecision {
            playbookInput = immutableObjectMap(playbookInput);
        }
    }

    public record SecurityAssessment(
        String action,
        List<String> categories,
        String reason,
        Double confidence
    ) {
        public SecurityAssessment {
            categories = categories == null ? List.of() : List.copyOf(categories);
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
        List<SessionMessage> recentMessages,
        List<SessionEvent> recentEvents
    ) {
        public AgentTurnRequest {
            availableAgents = availableAgents == null ? List.of() : List.copyOf(availableAgents);
            availablePlaybooks = availablePlaybooks == null ? List.of() : List.copyOf(availablePlaybooks);
            sharedState = immutableObjectMap(sharedState);
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        }
    }

    public record AgentTurnResult(
        AgentDecision decision,
        Map<String, Object> sharedState,
        PrivacyMappingTelemetry mappingTelemetry,
        SecurityAssessment securityAssessment
    ) {
        public AgentTurnResult {
            sharedState = immutableObjectMap(sharedState);
        }

        public AgentTurnResult(
            AgentDecision decision,
            Map<String, Object> sharedState,
            PrivacyMappingTelemetry mappingTelemetry
        ) {
            this(decision, sharedState, mappingTelemetry, null);
        }
    }

    public record AgentTurnExecutionOutcome(
        boolean success,
        AgentTurnResult result,
        String failureReason,
        List<LlmUsageEntry> llmUsage
    ) {
        public AgentTurnExecutionOutcome {
            llmUsage = llmUsage == null ? List.of() : List.copyOf(llmUsage);
        }

        public AgentTurnExecutionOutcome(
            boolean success,
            AgentTurnResult result,
            String failureReason
        ) {
            this(success, result, failureReason, List.of());
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
        SessionMessageInput message
    ) {
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
        String operatorId,
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
        SessionMessageInput message,
        Map<String, Object> payload
    ) {
        public HumanOperatorReplySignal {
            payload = immutableObjectMap(payload);
        }
    }

    public record EndHumanHandoffSignal(
        String sessionId,
        String operatorId
    ) {
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
        String operatorId,
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
        String operatorId,
        Map<String, Object> payload
    ) {
        public PlaybookProgressUpdate {
            payload = immutableObjectMap(payload);
        }
    }
}
