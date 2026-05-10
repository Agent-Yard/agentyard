package com.lynxus.contracts.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class SessionContracts {
    private SessionContracts() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
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
        NO_OP,
        SWITCH_OWNER,
        RUN_PLAYBOOK,
        SESSION_HUMAN_HANDOFF,
        SECURITY_BLOCK
    }

    public enum StreamVisibility {
        CUSTOMER,
        OPERATOR,
        DEVELOPER,
        INTERNAL
    }

    public enum AgentTurnStreamFrameKind {
        TURN_STARTED,
        MODEL_STARTED,
        MODEL_COMPLETED,
        ACTION_TOOL_STARTED,
        ACTION_TOOL_COMPLETED,
        REPLY_BLOCK_DELTA,
        REPLY_BLOCK_COMPLETED,
        FINAL_OUTCOME,
        ERROR
    }

    public enum AgentTurnTransientFrameKind {
        TURN_STARTED,
        MODEL_STARTED,
        MODEL_COMPLETED,
        ACTION_TOOL_STARTED,
        ACTION_TOOL_COMPLETED,
        REPLY_BLOCK_DELTA,
        REPLY_BLOCK_COMPLETED,
        TURN_COMPLETED,
        ERROR
    }

    public enum ModelStreamStatus {
        SUCCEEDED,
        FAILED,
        ABORTED
    }

    public enum TurnCompletionStatus {
        SUCCEEDED,
        FAILED
    }

    public enum ToolKind {
        CONTEXT_TOOL,
        STATE_TOOL,
        MESSAGE_BLOCK_TOOL,
        LIFECYCLE_ACTION_TOOL
    }

    public enum ToolCompletionStatus {
        ACCEPTED,
        REJECTED,
        FAILED
    }

    public enum StreamErrorStage {
        PROVIDER_STREAM,
        TOOL_ARGUMENT_PARSE,
        TOOL_EXECUTION,
        FINAL_OUTCOME_BUILD,
        TRANSCRIPT_PERSISTENCE
    }

    public enum SessionReplyDraftOperation {
        DELTA,
        COMPLETED,
        DISCARD
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
        boolean privateDeployment,
        Boolean enableThinking,
        String reasoningEffort
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
        String operatorReason
    ) {
        public AgentDecision {
            playbookInput = immutableObjectMap(playbookInput);
        }
    }

    @JsonDeserialize(using = AgentTurnStreamFrameJsonDeserializer.class)
    public record AgentTurnStreamFrame(
        String protocol,
        String frameId,
        String streamId,
        String sessionId,
        String turnId,
        String turnExecutionId,
        String ownerAgentId,
        long ownershipEpoch,
        long seq,
        AgentTurnStreamFrameKind kind,
        StreamVisibility visibility,
        Instant occurredAt,
        AgentTurnStreamPayload payload
    ) {
        public static final String PROTOCOL = "lynxus.agent-turn-stream.v1";

        public AgentTurnStreamFrame {
            payload = normalizePayload(kind, payload);
        }

        public AgentTurnStreamFrame(
            String protocol,
            String frameId,
            String streamId,
            String sessionId,
            String turnId,
            String turnExecutionId,
            String ownerAgentId,
            long ownershipEpoch,
            long seq,
            AgentTurnStreamFrameKind kind,
            StreamVisibility visibility,
            Instant occurredAt,
            Map<String, Object> payload
        ) {
            this(
                protocol,
                frameId,
                streamId,
                sessionId,
                turnId,
                turnExecutionId,
                ownerAgentId,
                ownershipEpoch,
                seq,
                kind,
                visibility,
                occurredAt,
                payloadFromMap(kind, payload)
            );
        }
    }

    @JsonDeserialize(using = AgentTurnTransientFrameJsonDeserializer.class)
    public record AgentTurnTransientFrame(
        String protocol,
        String frameId,
        String streamId,
        String sessionId,
        String turnId,
        String turnExecutionId,
        String ownerAgentId,
        long ownershipEpoch,
        long seq,
        AgentTurnTransientFrameKind kind,
        StreamVisibility visibility,
        Instant occurredAt,
        AgentTurnTransientPayload payload
    ) {
        public static final String PROTOCOL = "lynxus.agent-turn-transient.v1";

        public AgentTurnTransientFrame {
            payload = normalizeTransientPayload(kind, payload);
        }

        public AgentTurnTransientFrame(
            String protocol,
            String frameId,
            String streamId,
            String sessionId,
            String turnId,
            String turnExecutionId,
            String ownerAgentId,
            long ownershipEpoch,
            long seq,
            AgentTurnTransientFrameKind kind,
            StreamVisibility visibility,
            Instant occurredAt,
            Map<String, Object> payload
        ) {
            this(
                protocol,
                frameId,
                streamId,
                sessionId,
                turnId,
                turnExecutionId,
                ownerAgentId,
                ownershipEpoch,
                seq,
                kind,
                visibility,
                occurredAt,
                transientPayloadFromMap(kind, payload)
            );
        }

        public static AgentTurnTransientFrame fromStreamFrame(AgentTurnStreamFrame frame) {
            if (frame.kind() == AgentTurnStreamFrameKind.FINAL_OUTCOME) {
                throw new IllegalArgumentException("FINAL_OUTCOME cannot be converted to a transient frame");
            }
            if (!(frame.payload() instanceof AgentTurnTransientPayload transientPayload)) {
                throw new IllegalArgumentException("stream payload is not transient");
            }
            return new AgentTurnTransientFrame(
                AgentTurnTransientFrame.PROTOCOL,
                frame.frameId(),
                frame.streamId(),
                frame.sessionId(),
                frame.turnId(),
                frame.turnExecutionId(),
                frame.ownerAgentId(),
                frame.ownershipEpoch(),
                frame.seq(),
                AgentTurnTransientFrameKind.valueOf(frame.kind().name()),
                frame.visibility(),
                frame.occurredAt(),
                transientPayload
            );
        }
    }

    public sealed interface AgentTurnStreamPayload permits
        TurnStartedPayload,
        ModelStartedPayload,
        ModelCompletedPayload,
        ToolStartedPayload,
        ToolCompletedPayload,
        ReplyBlockDeltaPayload,
        ReplyBlockCompletedPayload,
        FinalOutcomePayload,
        ErrorPayload {
    }

    public sealed interface AgentTurnTransientPayload permits
        TurnStartedPayload,
        ModelStartedPayload,
        ModelCompletedPayload,
        ToolStartedPayload,
        ToolCompletedPayload,
        ReplyBlockDeltaPayload,
        ReplyBlockCompletedPayload,
        TurnCompletedPayload,
        ErrorPayload {
    }

    public record TurnStartedPayload(String messageId, SessionTriggerType triggerType) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ModelStartedPayload(String modelRoundId) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ModelCompletedPayload(String modelRoundId, ModelStreamStatus status) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ToolStartedPayload(String modelRoundId, String toolCallId, String toolName, ToolKind toolKind) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ToolProducedPayload(String action, String messageBlockId, Boolean sharedStateUpdated) {
    }

    public record ToolCompletedPayload(
        String toolCallId,
        String toolName,
        ToolKind toolKind,
        ToolCompletionStatus status,
        ToolProducedPayload produced
    ) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ReplyBlockDeltaPayload(
        String messageId,
        String blockId,
        SessionMessageBlockType blockType,
        String delta
    ) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record ReplyBlockCompletedPayload(String messageId, String blockId, Object block) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
    }

    public record FinalOutcomePayload(String messageId, AgentTurnExecutionOutcome outcome) implements AgentTurnStreamPayload {
    }

    public record TurnCompletedPayload(String messageId, TurnCompletionStatus status) implements AgentTurnTransientPayload {
    }

    public record ErrorPayload(
        String code,
        String messageId,
        String message,
        StreamErrorStage stage,
        boolean retryable,
        Map<String, Object> details
    ) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
        public ErrorPayload {
            details = immutableObjectMap(details);
        }
    }

    public static final class AgentTurnStreamFrameJsonDeserializer extends ValueDeserializer<AgentTurnStreamFrame> {
        @Override
        public AgentTurnStreamFrame deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            JsonNode node = context.readTree(parser);
            AgentTurnStreamFrameKind kind = jsonEnumValue(
                context,
                requiredText(context, node, "kind"),
                AgentTurnStreamFrameKind.class,
                "kind"
            );
            try {
                return new AgentTurnStreamFrame(
                    requiredText(context, node, "protocol"),
                    requiredText(context, node, "frameId"),
                    requiredText(context, node, "streamId"),
                    requiredText(context, node, "sessionId"),
                    requiredText(context, node, "turnId"),
                    requiredText(context, node, "turnExecutionId"),
                    requiredText(context, node, "ownerAgentId"),
                    requiredLong(context, node, "ownershipEpoch"),
                    requiredLong(context, node, "seq"),
                    kind,
                    jsonEnumValue(context, requiredText(context, node, "visibility"), StreamVisibility.class, "visibility"),
                    Instant.parse(requiredText(context, node, "occurredAt")),
                    payloadFromJson(context, kind, requiredObject(context, node, "payload"))
                );
            } catch (IllegalArgumentException error) {
                return context.reportInputMismatch(AgentTurnStreamFrame.class, error.getMessage());
            }
        }
    }

    public static final class AgentTurnTransientFrameJsonDeserializer extends ValueDeserializer<AgentTurnTransientFrame> {
        @Override
        public AgentTurnTransientFrame deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            JsonNode node = context.readTree(parser);
            AgentTurnTransientFrameKind kind = jsonEnumValue(
                context,
                requiredText(context, node, "kind"),
                AgentTurnTransientFrameKind.class,
                "kind"
            );
            try {
                return new AgentTurnTransientFrame(
                    requiredText(context, node, "protocol"),
                    requiredText(context, node, "frameId"),
                    requiredText(context, node, "streamId"),
                    requiredText(context, node, "sessionId"),
                    requiredText(context, node, "turnId"),
                    requiredText(context, node, "turnExecutionId"),
                    requiredText(context, node, "ownerAgentId"),
                    requiredLong(context, node, "ownershipEpoch"),
                    requiredLong(context, node, "seq"),
                    kind,
                    jsonEnumValue(context, requiredText(context, node, "visibility"), StreamVisibility.class, "visibility"),
                    Instant.parse(requiredText(context, node, "occurredAt")),
                    transientPayloadFromJson(context, kind, requiredObject(context, node, "payload"))
                );
            } catch (IllegalArgumentException error) {
                return context.reportInputMismatch(AgentTurnTransientFrame.class, error.getMessage());
            }
        }
    }

    private static AgentTurnStreamPayload normalizePayload(AgentTurnStreamFrameKind kind, AgentTurnStreamPayload payload) {
        if (kind == null) {
            throw new IllegalArgumentException("agent turn stream frame kind is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("agent turn stream frame payload is required");
        }
        boolean matches = switch (kind) {
            case TURN_STARTED -> payload instanceof TurnStartedPayload;
            case MODEL_STARTED -> payload instanceof ModelStartedPayload;
            case MODEL_COMPLETED -> payload instanceof ModelCompletedPayload;
            case ACTION_TOOL_STARTED -> payload instanceof ToolStartedPayload;
            case ACTION_TOOL_COMPLETED -> payload instanceof ToolCompletedPayload;
            case REPLY_BLOCK_DELTA -> payload instanceof ReplyBlockDeltaPayload;
            case REPLY_BLOCK_COMPLETED -> payload instanceof ReplyBlockCompletedPayload;
            case FINAL_OUTCOME -> payload instanceof FinalOutcomePayload;
            case ERROR -> payload instanceof ErrorPayload;
        };
        if (!matches) {
            throw new IllegalArgumentException("agent turn stream payload does not match kind " + kind);
        }
        validatePayloadContent(payload);
        return payload;
    }

    private static AgentTurnTransientPayload normalizeTransientPayload(
        AgentTurnTransientFrameKind kind,
        AgentTurnTransientPayload payload
    ) {
        if (kind == null) {
            throw new IllegalArgumentException("agent turn transient frame kind is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("agent turn transient frame payload is required");
        }
        boolean matches = switch (kind) {
            case TURN_STARTED -> payload instanceof TurnStartedPayload;
            case MODEL_STARTED -> payload instanceof ModelStartedPayload;
            case MODEL_COMPLETED -> payload instanceof ModelCompletedPayload;
            case ACTION_TOOL_STARTED -> payload instanceof ToolStartedPayload;
            case ACTION_TOOL_COMPLETED -> payload instanceof ToolCompletedPayload;
            case REPLY_BLOCK_DELTA -> payload instanceof ReplyBlockDeltaPayload;
            case REPLY_BLOCK_COMPLETED -> payload instanceof ReplyBlockCompletedPayload;
            case TURN_COMPLETED -> payload instanceof TurnCompletedPayload;
            case ERROR -> payload instanceof ErrorPayload;
        };
        if (!matches) {
            throw new IllegalArgumentException("agent turn transient payload does not match kind " + kind);
        }
        validatePayloadContent(payload);
        return payload;
    }

    private static void validatePayloadContent(Object payload) {
        switch (payload) {
            case TurnStartedPayload turnStarted -> {
                requirePayloadText(turnStarted.messageId(), "payload.messageId");
                requirePayloadValue(turnStarted.triggerType(), "payload.triggerType");
            }
            case ModelStartedPayload modelStarted -> requirePayloadText(modelStarted.modelRoundId(), "payload.modelRoundId");
            case ModelCompletedPayload modelCompleted -> {
                requirePayloadText(modelCompleted.modelRoundId(), "payload.modelRoundId");
                requirePayloadValue(modelCompleted.status(), "payload.status");
            }
            case ToolStartedPayload toolStarted -> {
                requirePayloadText(toolStarted.modelRoundId(), "payload.modelRoundId");
                requirePayloadText(toolStarted.toolCallId(), "payload.toolCallId");
                requirePayloadText(toolStarted.toolName(), "payload.toolName");
                requirePayloadValue(toolStarted.toolKind(), "payload.toolKind");
            }
            case ToolCompletedPayload toolCompleted -> {
                requirePayloadText(toolCompleted.toolCallId(), "payload.toolCallId");
                requirePayloadText(toolCompleted.toolName(), "payload.toolName");
                requirePayloadValue(toolCompleted.toolKind(), "payload.toolKind");
                requirePayloadValue(toolCompleted.status(), "payload.status");
            }
            case ReplyBlockDeltaPayload replyBlockDelta -> {
                requirePayloadText(replyBlockDelta.messageId(), "payload.messageId");
                requirePayloadText(replyBlockDelta.blockId(), "payload.blockId");
                if (replyBlockDelta.blockType() != SessionMessageBlockType.TEXT) {
                    throw new IllegalArgumentException("payload.blockType must be TEXT");
                }
                requirePayloadNonEmptyString(replyBlockDelta.delta(), "payload.delta");
            }
            case ReplyBlockCompletedPayload replyBlockCompleted -> {
                requirePayloadText(replyBlockCompleted.messageId(), "payload.messageId");
                requirePayloadText(replyBlockCompleted.blockId(), "payload.blockId");
                requirePayloadValue(replyBlockCompleted.block(), "payload.block");
            }
            case FinalOutcomePayload finalOutcome -> {
                requirePayloadText(finalOutcome.messageId(), "payload.messageId");
                requirePayloadValue(finalOutcome.outcome(), "payload.outcome");
            }
            case TurnCompletedPayload turnCompleted -> {
                requirePayloadText(turnCompleted.messageId(), "payload.messageId");
                requirePayloadValue(turnCompleted.status(), "payload.status");
            }
            case ErrorPayload errorPayload -> {
                requirePayloadText(errorPayload.code(), "payload.code");
                requirePayloadText(errorPayload.messageId(), "payload.messageId");
                requirePayloadText(errorPayload.message(), "payload.message");
                requirePayloadValue(errorPayload.stage(), "payload.stage");
            }
            default -> throw new IllegalArgumentException("unsupported agent turn payload type");
        }
    }

    private static void requirePayloadText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
    }

    private static void requirePayloadNonEmptyString(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
    }

    private static void requirePayloadValue(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static AgentTurnStreamPayload payloadFromMap(AgentTurnStreamFrameKind kind, Map<String, Object> payload) {
        if (kind == null) {
            throw new IllegalArgumentException("agent turn stream frame kind is required");
        }
        Map<String, Object> source = immutableObjectMap(payload);
        return switch (kind) {
            case TURN_STARTED -> {
                requireMapFields(kind, source, Set.of("messageId", "triggerType"), Set.of("messageId", "triggerType"));
                yield new TurnStartedPayload(
                    requiredString(source, "messageId"),
                    requiredEnum(source, "triggerType", SessionTriggerType.class)
                );
            }
            case MODEL_STARTED -> {
                requireMapFields(kind, source, Set.of("modelRoundId"), Set.of("modelRoundId"));
                yield new ModelStartedPayload(requiredString(source, "modelRoundId"));
            }
            case MODEL_COMPLETED -> {
                requireMapFields(kind, source, Set.of("modelRoundId", "status"), Set.of("modelRoundId", "status"));
                yield new ModelCompletedPayload(
                    requiredString(source, "modelRoundId"),
                    requiredEnum(source, "status", ModelStreamStatus.class)
                );
            }
            case ACTION_TOOL_STARTED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("modelRoundId", "toolCallId", "toolName", "toolKind"),
                    Set.of("modelRoundId", "toolCallId", "toolName", "toolKind")
                );
                yield new ToolStartedPayload(
                    requiredString(source, "modelRoundId"),
                    requiredString(source, "toolCallId"),
                    requiredString(source, "toolName"),
                    requiredEnum(source, "toolKind", ToolKind.class)
                );
            }
            case ACTION_TOOL_COMPLETED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("toolCallId", "toolName", "toolKind", "status"),
                    Set.of("toolCallId", "toolName", "toolKind", "status", "produced")
                );
                yield new ToolCompletedPayload(
                    requiredString(source, "toolCallId"),
                    requiredString(source, "toolName"),
                    requiredEnum(source, "toolKind", ToolKind.class),
                    requiredEnum(source, "status", ToolCompletionStatus.class),
                    optionalToolProduced(source.get("produced"))
                );
            }
            case REPLY_BLOCK_DELTA -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("messageId", "blockId", "blockType", "delta"),
                    Set.of("messageId", "blockId", "blockType", "delta")
                );
                yield new ReplyBlockDeltaPayload(
                    requiredString(source, "messageId"),
                    requiredString(source, "blockId"),
                    requiredEnum(source, "blockType", SessionMessageBlockType.class),
                    requiredNonEmptyString(source, "delta")
                );
            }
            case REPLY_BLOCK_COMPLETED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("messageId", "blockId", "block"),
                    Set.of("messageId", "blockId", "block")
                );
                yield new ReplyBlockCompletedPayload(
                    requiredString(source, "messageId"),
                    requiredString(source, "blockId"),
                    requiredValue(source, "block")
                );
            }
            case FINAL_OUTCOME -> {
                requireMapFields(kind, source, Set.of("messageId", "outcome"), Set.of("messageId", "outcome"));
                Object outcome = requiredValue(source, "outcome");
                if (!(outcome instanceof AgentTurnExecutionOutcome typedOutcome)) {
                    throw new IllegalArgumentException("FINAL_OUTCOME payload.outcome must be AgentTurnExecutionOutcome");
                }
                yield new FinalOutcomePayload(requiredString(source, "messageId"), typedOutcome);
            }
            case ERROR -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("code", "messageId", "message", "stage", "retryable"),
                    Set.of("code", "messageId", "message", "stage", "retryable", "details")
                );
                yield new ErrorPayload(
                    requiredString(source, "code"),
                    requiredString(source, "messageId"),
                    requiredString(source, "message"),
                    requiredEnum(source, "stage", StreamErrorStage.class),
                    requiredBoolean(source, "retryable"),
                    optionalObjectMap(source.get("details"))
                );
            }
        };
    }

    private static AgentTurnTransientPayload transientPayloadFromMap(
        AgentTurnTransientFrameKind kind,
        Map<String, Object> payload
    ) {
        if (kind == null) {
            throw new IllegalArgumentException("agent turn transient frame kind is required");
        }
        Map<String, Object> source = immutableObjectMap(payload);
        return switch (kind) {
            case TURN_STARTED -> {
                requireMapFields(kind, source, Set.of("messageId", "triggerType"), Set.of("messageId", "triggerType"));
                yield new TurnStartedPayload(
                    requiredString(source, "messageId"),
                    requiredEnum(source, "triggerType", SessionTriggerType.class)
                );
            }
            case MODEL_STARTED -> {
                requireMapFields(kind, source, Set.of("modelRoundId"), Set.of("modelRoundId"));
                yield new ModelStartedPayload(requiredString(source, "modelRoundId"));
            }
            case MODEL_COMPLETED -> {
                requireMapFields(kind, source, Set.of("modelRoundId", "status"), Set.of("modelRoundId", "status"));
                yield new ModelCompletedPayload(
                    requiredString(source, "modelRoundId"),
                    requiredEnum(source, "status", ModelStreamStatus.class)
                );
            }
            case ACTION_TOOL_STARTED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("modelRoundId", "toolCallId", "toolName", "toolKind"),
                    Set.of("modelRoundId", "toolCallId", "toolName", "toolKind")
                );
                yield new ToolStartedPayload(
                    requiredString(source, "modelRoundId"),
                    requiredString(source, "toolCallId"),
                    requiredString(source, "toolName"),
                    requiredEnum(source, "toolKind", ToolKind.class)
                );
            }
            case ACTION_TOOL_COMPLETED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("toolCallId", "toolName", "toolKind", "status"),
                    Set.of("toolCallId", "toolName", "toolKind", "status", "produced")
                );
                yield new ToolCompletedPayload(
                    requiredString(source, "toolCallId"),
                    requiredString(source, "toolName"),
                    requiredEnum(source, "toolKind", ToolKind.class),
                    requiredEnum(source, "status", ToolCompletionStatus.class),
                    optionalToolProduced(source.get("produced"))
                );
            }
            case REPLY_BLOCK_DELTA -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("messageId", "blockId", "blockType", "delta"),
                    Set.of("messageId", "blockId", "blockType", "delta")
                );
                yield new ReplyBlockDeltaPayload(
                    requiredString(source, "messageId"),
                    requiredString(source, "blockId"),
                    requiredEnum(source, "blockType", SessionMessageBlockType.class),
                    requiredNonEmptyString(source, "delta")
                );
            }
            case REPLY_BLOCK_COMPLETED -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("messageId", "blockId", "block"),
                    Set.of("messageId", "blockId", "block")
                );
                yield new ReplyBlockCompletedPayload(
                    requiredString(source, "messageId"),
                    requiredString(source, "blockId"),
                    requiredValue(source, "block")
                );
            }
            case TURN_COMPLETED -> {
                requireMapFields(kind, source, Set.of("messageId", "status"), Set.of("messageId", "status"));
                yield new TurnCompletedPayload(
                    requiredString(source, "messageId"),
                    requiredEnum(source, "status", TurnCompletionStatus.class)
                );
            }
            case ERROR -> {
                requireMapFields(
                    kind,
                    source,
                    Set.of("code", "messageId", "message", "stage", "retryable"),
                    Set.of("code", "messageId", "message", "stage", "retryable", "details")
                );
                yield new ErrorPayload(
                    requiredString(source, "code"),
                    requiredString(source, "messageId"),
                    requiredString(source, "message"),
                    requiredEnum(source, "stage", StreamErrorStage.class),
                    requiredBoolean(source, "retryable"),
                    optionalObjectMap(source.get("details"))
                );
            }
        };
    }

    private static AgentTurnStreamPayload payloadFromJson(
        DeserializationContext context,
        AgentTurnStreamFrameKind kind,
        JsonNode payload
    ) throws JacksonException {
        return switch (kind) {
            case TURN_STARTED -> readPayload(
                context,
                kind,
                payload,
                TurnStartedPayload.class,
                Set.of("messageId", "triggerType"),
                Set.of("messageId", "triggerType")
            );
            case MODEL_STARTED -> readPayload(
                context,
                kind,
                payload,
                ModelStartedPayload.class,
                Set.of("modelRoundId"),
                Set.of("modelRoundId")
            );
            case MODEL_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ModelCompletedPayload.class,
                Set.of("modelRoundId", "status"),
                Set.of("modelRoundId", "status")
            );
            case ACTION_TOOL_STARTED -> readPayload(
                context,
                kind,
                payload,
                ToolStartedPayload.class,
                Set.of("modelRoundId", "toolCallId", "toolName", "toolKind"),
                Set.of("modelRoundId", "toolCallId", "toolName", "toolKind")
            );
            case ACTION_TOOL_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ToolCompletedPayload.class,
                Set.of("toolCallId", "toolName", "toolKind", "status"),
                Set.of("toolCallId", "toolName", "toolKind", "status", "produced")
            );
            case REPLY_BLOCK_DELTA -> readPayload(
                context,
                kind,
                payload,
                ReplyBlockDeltaPayload.class,
                Set.of("messageId", "blockId", "blockType", "delta"),
                Set.of("messageId", "blockId", "blockType", "delta")
            );
            case REPLY_BLOCK_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ReplyBlockCompletedPayload.class,
                Set.of("messageId", "blockId", "block"),
                Set.of("messageId", "blockId", "block")
            );
            case FINAL_OUTCOME -> readPayload(
                context,
                kind,
                payload,
                FinalOutcomePayload.class,
                Set.of("messageId", "outcome"),
                Set.of("messageId", "outcome")
            );
            case ERROR -> readPayload(
                context,
                kind,
                payload,
                ErrorPayload.class,
                Set.of("code", "messageId", "message", "stage", "retryable"),
                Set.of("code", "messageId", "message", "stage", "retryable", "details")
            );
        };
    }

    private static AgentTurnTransientPayload transientPayloadFromJson(
        DeserializationContext context,
        AgentTurnTransientFrameKind kind,
        JsonNode payload
    ) throws JacksonException {
        return switch (kind) {
            case TURN_STARTED -> readPayload(
                context,
                kind,
                payload,
                TurnStartedPayload.class,
                Set.of("messageId", "triggerType"),
                Set.of("messageId", "triggerType")
            );
            case MODEL_STARTED -> readPayload(
                context,
                kind,
                payload,
                ModelStartedPayload.class,
                Set.of("modelRoundId"),
                Set.of("modelRoundId")
            );
            case MODEL_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ModelCompletedPayload.class,
                Set.of("modelRoundId", "status"),
                Set.of("modelRoundId", "status")
            );
            case ACTION_TOOL_STARTED -> readPayload(
                context,
                kind,
                payload,
                ToolStartedPayload.class,
                Set.of("modelRoundId", "toolCallId", "toolName", "toolKind"),
                Set.of("modelRoundId", "toolCallId", "toolName", "toolKind")
            );
            case ACTION_TOOL_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ToolCompletedPayload.class,
                Set.of("toolCallId", "toolName", "toolKind", "status"),
                Set.of("toolCallId", "toolName", "toolKind", "status", "produced")
            );
            case REPLY_BLOCK_DELTA -> readPayload(
                context,
                kind,
                payload,
                ReplyBlockDeltaPayload.class,
                Set.of("messageId", "blockId", "blockType", "delta"),
                Set.of("messageId", "blockId", "blockType", "delta")
            );
            case REPLY_BLOCK_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                ReplyBlockCompletedPayload.class,
                Set.of("messageId", "blockId", "block"),
                Set.of("messageId", "blockId", "block")
            );
            case TURN_COMPLETED -> readPayload(
                context,
                kind,
                payload,
                TurnCompletedPayload.class,
                Set.of("messageId", "status"),
                Set.of("messageId", "status")
            );
            case ERROR -> readPayload(
                context,
                kind,
                payload,
                ErrorPayload.class,
                Set.of("code", "messageId", "message", "stage", "retryable"),
                Set.of("code", "messageId", "message", "stage", "retryable", "details")
            );
        };
    }

    private static <T> T readPayload(
        DeserializationContext context,
        Object kind,
        JsonNode payload,
        Class<T> payloadType,
        Set<String> requiredFields,
        Set<String> allowedFields
    ) throws JacksonException {
        requireJsonFields(context, kind, payload, requiredFields, allowedFields);
        return context.readTreeAsValue(payload, payloadType);
    }

    private static void requireJsonFields(
        DeserializationContext context,
        Object kind,
        JsonNode payload,
        Set<String> requiredFields,
        Set<String> allowedFields
    ) throws JacksonException {
        Set<String> actualFields = Set.copyOf(payload.propertyNames());
        if (!actualFields.containsAll(requiredFields) || !allowedFields.containsAll(actualFields)) {
            context.reportInputMismatch(
                AgentTurnStreamFrame.class,
                "%s payload fields must be compatible with required=%s allowed=%s actual=%s",
                kind,
                requiredFields,
                allowedFields,
                actualFields
            );
        }
        for (String requiredField : requiredFields) {
            JsonNode value = payload.get(requiredField);
            if (value == null || value.isNull()) {
                context.reportInputMismatch(AgentTurnStreamFrame.class, "%s payload.%s is required", kind, requiredField);
            }
        }
    }

    private static JsonNode requiredObject(
        DeserializationContext context,
        JsonNode node,
        String field
    ) throws JacksonException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isObject()) {
            return context.reportInputMismatch(AgentTurnStreamFrame.class, "%s must be an object", field);
        }
        return value;
    }

    private static String requiredText(
        DeserializationContext context,
        JsonNode node,
        String field
    ) throws JacksonException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return context.reportInputMismatch(AgentTurnStreamFrame.class, "%s must be a non-empty string", field);
        }
        return value.stringValue();
    }

    private static long requiredLong(
        DeserializationContext context,
        JsonNode node,
        String field
    ) throws JacksonException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return context.reportInputMismatch(AgentTurnStreamFrame.class, "%s must be an integer", field);
        }
        return value.longValue();
    }

    private static <T extends Enum<T>> T jsonEnumValue(
        DeserializationContext context,
        String value,
        Class<T> enumType,
        String field
    ) throws JacksonException {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException error) {
            return context.reportInputMismatch(AgentTurnStreamFrame.class, "%s has unsupported value %s", field, value);
        }
    }

    private static void requireMapFields(
        Object kind,
        Map<String, Object> payload,
        Set<String> requiredFields,
        Set<String> allowedFields
    ) {
        Set<String> actualFields = payload.keySet();
        if (!actualFields.containsAll(requiredFields) || !allowedFields.containsAll(actualFields)) {
            throw new IllegalArgumentException(
                kind + " payload fields must be compatible with required=" + requiredFields
                    + " allowed=" + allowedFields
                    + " actual=" + actualFields
            );
        }
    }

    private static Object requiredValue(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requiredString(Map<String, Object> payload, String field) {
        Object value = requiredValue(payload, field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(field + " must be a non-empty string");
    }

    private static String requiredNonEmptyString(Map<String, Object> payload, String field) {
        Object value = requiredValue(payload, field);
        if (value instanceof String text && !text.isEmpty()) {
            return text;
        }
        throw new IllegalArgumentException(field + " must be a non-empty string");
    }

    private static String optionalString(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static boolean requiredBoolean(Map<String, Object> payload, String field) {
        Object value = requiredValue(payload, field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static <T extends Enum<T>> T requiredEnum(Map<String, Object> payload, String field, Class<T> enumType) {
        Object value = requiredValue(payload, field);
        if (enumType.isInstance(value)) {
            return enumType.cast(value);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Enum.valueOf(enumType, text);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(field + " has unsupported value " + text, error);
            }
        }
        throw new IllegalArgumentException(field + " must be " + enumType.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalObjectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return immutableObjectMap((Map<String, Object>) map);
        }
        throw new IllegalArgumentException("details must be an object");
    }

    @SuppressWarnings("unchecked")
    private static ToolProducedPayload optionalToolProduced(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ToolProducedPayload produced) {
            return produced;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> produced = immutableObjectMap((Map<String, Object>) map);
            Set<String> allowedFields = Set.of("action", "messageBlockId", "sharedStateUpdated");
            if (!allowedFields.containsAll(produced.keySet())) {
                throw new IllegalArgumentException("produced payload fields must be compatible with allowed=" + allowedFields);
            }
            Object sharedStateUpdated = produced.get("sharedStateUpdated");
            if (sharedStateUpdated != null && !(sharedStateUpdated instanceof Boolean)) {
                throw new IllegalArgumentException("produced.sharedStateUpdated must be a boolean");
            }
            return new ToolProducedPayload(
                optionalString(produced, "action"),
                optionalString(produced, "messageBlockId"),
                (Boolean) sharedStateUpdated
            );
        }
        throw new IllegalArgumentException("produced must be an object");
    }

    public record SessionProgressEvent(
        String id,
        String type,
        Instant occurredAt,
        String sessionId,
        String turnId,
        StreamVisibility visibility,
        String phase,
        String status,
        String title,
        Map<String, Object> detail
    ) {
        public SessionProgressEvent {
            detail = immutableObjectMap(detail);
        }
    }

    public record SessionReplyDraftEvent(
        String id,
        String type,
        Instant occurredAt,
        String sessionId,
        String turnId,
        String messageId,
        SessionReplyDraftOperation operation,
        String blockId,
        SessionMessageBlockType blockType,
        String delta,
        String text
    ) {
    }

    public record SessionStreamErrorEvent(
        String id,
        String type,
        Instant occurredAt,
        String sessionId,
        String turnId,
        String code,
        String message,
        boolean retryable,
        Map<String, Object> detail
    ) {
        public SessionStreamErrorEvent {
            detail = immutableObjectMap(detail);
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
        String turnId,
        String turnExecutionId,
        String replyMessageId,
        long ownershipEpoch,
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

    public record ChannelInboundSessionMessageRequest(
        String channelProfileId,
        String externalConversationId,
        String externalMessageId,
        String inboundEventId,
        String dedupKey,
        String assistantId,
        String customerId,
        String sessionId,
        SessionMessageInput message
    ) {
    }

    public record ChannelInboundSessionMessageResponse(
        String sessionId,
        String status
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
