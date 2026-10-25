package com.agentyard.contracts.agent;

import com.agentyard.contracts.runtime.RuntimeContracts.*;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent participants, bounded activations and tools share one execution boundary. */
public final class AgentContracts {
    private AgentContracts() {}

    public static Map<String, Object> object(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    public enum SessionStatus { ACTIVE, PAUSED, CLOSED }
    public enum ActivationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum MessageKind { MESSAGE, DELEGATION, RESULT, QUESTION, EVENT, CONTROL }
    public enum DeliveryStatus { PENDING, ACCEPTED, PROCESSED, FAILED, CANCELLED }
    public enum ToolRunStatus { QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED }
    public enum ScopeKind { PLATFORM, ORGANIZATION, TASK, SESSION, TEMPLATE, RESOURCE, PARTICIPANT }

    /** Configuration is versioned independently; it does not grant any permission. */
    public record AgentProfile(String name, String role, String responsibility, String systemPrompt,
        LlmModelDescriptor model, LlmModelDescriptor effectivePrivacyModelBinding,
        boolean effectivePrivacyMappingEnabled, KnowledgeBindingDescriptor knowledgeBinding,
        int memoryWindowSize, List<SkillDescriptor> skills, List<ToolDescriptor> tools,
        List<PlaybookConfig> playbooks) {
        public AgentProfile {
            skills = skills == null ? List.of() : List.copyOf(skills);
            tools = tools == null ? List.of() : List.copyOf(tools);
            playbooks = playbooks == null ? List.of() : List.copyOf(playbooks);
        }
    }
    public record Session(String id, String participantId, String templateVersionId, String name,
        SessionStatus status, String createdBy, String replyToParticipantId,
        Map<String, Object> workMemory, long revision, Instant createdAt, Instant updatedAt) {
        public Session { workMemory = object(workMemory); }
    }
    public record ReceptionBinding(String participantId, String entryId, String sessionId, Instant createdAt) {}
    public record Message(String id, String senderId, String recipientId, String taskId,
        String replyToMessageId, MessageKind kind, Map<String, Object> body, Instant createdAt) {
        public Message { body = object(body); }
    }
    public record Delivery(String id, String messageId, String sessionId, DeliveryStatus status,
        String activationId, String failureReason, Instant createdAt, Instant updatedAt) {}
    public record Activation(String id, String sessionId, String inputMessageId, ActivationStatus status,
        String failureReason, Instant createdAt, Instant updatedAt, String completion) {}
    public record Grant(String id, String participantId, String operation, ScopeKind scopeKind,
        String scopeId, boolean delegable, String grantedBy, String parentGrantId,
        Instant expiresAt, Instant revokedAt, Instant createdAt) {}

    /** Only the trusted control plane assembles this request from a persisted activation. */
    public record ActivationRuntime(String activationId, String sessionId, String participantId,
        String templateVersionId, AgentProfile agent, Message trigger, Map<String, Object> context,
        Map<String, Object> workMemory, int maxToolSteps) {
        public ActivationRuntime { context = object(context); workMemory = object(workMemory); }
    }
    public record ActivationStart(String activationId) {}
    public record ActivationOutcome(boolean success, MessageContent replyMessage,
        Map<String, Object> workMemory, String failureReason, List<LlmUsageEntry> llmUsage,
        PrivacyMappingTelemetry mappingTelemetry, SecurityAssessment securityAssessment,
        String completion, boolean retryable) {
        public ActivationOutcome {
            workMemory = object(workMemory);
            llmUsage = llmUsage == null ? List.of() : List.copyOf(llmUsage);
            if (success && (completion == null || !java.util.Set.of("COMPLETED", "WAITING", "BLOCKED").contains(completion) || retryable))
                throw new IllegalArgumentException("successful activation requires an explicit terminal disposition");
        }
    }
    public record ToolInvocation(String callId, String name, Map<String, Object> arguments) {
        public ToolInvocation { arguments = object(arguments); }
    }
    public record ToolResult(boolean success, Object result, String code, String message) {}

    public record ToolRun(String id, String sessionId, String participantId, String activationId,
        String taskId, String playbookVersionId, ToolRunStatus status, Map<String, Object> input,
        Map<String, Object> result, Map<String, Object> waiting, String failureReason,
        Instant createdAt, Instant updatedAt) {
        public ToolRun { input = object(input); result = object(result); waiting = object(waiting); }
    }
    public record ToolRunRuntime(ToolRun run, PlaybookConfig playbook, AgentProfile agent) {}
    public record ToolRunStart(String toolRunId) {}
    public record ToolRunProgress(ToolRunStatus status, Map<String, Object> result,
        Map<String, Object> waiting, String failureReason) {
        public ToolRunProgress { result = object(result); waiting = object(waiting); }
    }
    public record ToolRunResume(String signalId, String waitingId, String participantId,
        String sourceMessageId, PlaybookResumeSource source, Map<String, Object> payload) {
        public ToolRunResume { payload = object(payload); }
    }
    public record ToolRunNodeAuthorization(String nodeKey, String executionId) {}
    public record ToolRunNodeRequest(String toolRunId, String nodeKey, String executionId,
        Map<String, Object> input) {
        public ToolRunNodeRequest { input = object(input); }
    }
    public record Schedule(String id, String sessionId, String taskId, Instant dueAt,
        String instruction, Long intervalSeconds, boolean active, String createdBy, Instant createdAt) {}
    public record Subscription(String id, String sessionId, ScopeKind scopeKind, String scopeId,
        List<String> eventKinds, boolean active, String createdBy, Instant createdAt) {
        public Subscription { eventKinds = eventKinds == null ? List.of() : List.copyOf(eventKinds); }
    }
    public record CreateSession(String requestId, String templateVersionId, String name,
        String taskId, String instruction, List<GrantRequest> grants, List<String> taskRoles) {
        public CreateSession {
            grants = grants == null ? List.of() : List.copyOf(grants);
            taskRoles = taskRoles == null ? List.of() : List.copyOf(taskRoles);
        }
    }
    public record GrantRequest(String operation, ScopeKind scopeKind, String scopeId, boolean delegable,
        Instant expiresAt) {}
    public record SendMessage(String requestId, String recipientId, String taskId, String replyToMessageId,
        MessageKind kind, Map<String, Object> body) {
        public SendMessage { body = object(body); }
    }
    public record ReceptionRequest(String entryId) {}
    public record Reception(Session session, String humanParticipantId, List<Message> messages, Activation latestActivation,
        List<com.agentyard.contracts.task.TaskContracts.Actor> participants,String previousCursor) {}
    public record SessionDetail(Session session, List<Message> messages, List<Activation> activations,
        List<ToolRun> toolRuns, List<Schedule> schedules, List<Subscription> subscriptions) {}
    public record ExecutionStatus(String sessionId, SessionStatus lifecycle, String execution,
        Instant updatedAt, boolean historyReadable) {}
}
