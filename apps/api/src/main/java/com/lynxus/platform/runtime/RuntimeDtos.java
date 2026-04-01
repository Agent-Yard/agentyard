package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationAction;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEvent;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventSource;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionOutcome;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionResult;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionTask;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ModelHitSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.PauseReasonSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolInvocationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;

public final class RuntimeDtos {
    private RuntimeDtos() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record TaskLaunchRequest(String scenarioId, String assistantId, String question, String customerId) {
    }

    public record ResumeActionRequest(String type, String comment, String userId, Map<String, String> attributes) {
    }

    public record ConversationMessageInputDto(
        ConversationPayloadType payloadType,
        Map<String, Object> payload
    ) {
        public ConversationMessageInputDto {
            payload = immutableObjectMap(payload);
        }
    }

    public record CreateConversationSessionRequest(String scenarioId, String assistantId, String customerId, ConversationMessageInputDto openingMessage) {
    }

    public record ConversationMessageRequest(
        String customerId,
        ConversationPayloadType payloadType,
        Map<String, Object> payload
    ) {
        public ConversationMessageRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record CreateExternalInteractionTaskRequest(
        String sessionId,
        String workflowInstanceId,
        ExternalInteractionType interactionType,
        String title,
        String instruction,
        String provider,
        String providerReference,
        String launchUrl,
        String returnPath,
        Instant expiresAt,
        String primaryActionLabel,
        List<ConversationAction> secondaryActions,
        Map<String, Object> displayHints
    ) {
        public CreateExternalInteractionTaskRequest {
            secondaryActions = secondaryActions == null ? List.of() : List.copyOf(secondaryActions);
            displayHints = immutableObjectMap(displayHints);
        }
    }

    public record ExternalInteractionReturnRequest(
        String returnToken,
        String providerReference,
        String dedupeKey,
        Map<String, Object> payload
    ) {
        public ExternalInteractionReturnRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record ExternalInteractionCallbackRequest(
        String taskId,
        String providerReference,
        String dedupeKey,
        Map<String, Object> payload,
        ExternalInteractionResult result
    ) {
        public ExternalInteractionCallbackRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record TaskInstanceDto(
        String id,
        String scenarioId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        String question,
        String customerId,
        TaskStatus status,
        Instant createdAt,
        String workflowInstanceId
    ) {
    }

    public record WorkflowInstanceDto(
        String id,
        String taskId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        WorkflowStatus status,
        String summary,
        String finalReply,
        String currentNodeKey,
        boolean escalationRequired,
        ExecutionCheckpoint checkpoint,
        ResumeTaskSnapshot resumeTask,
        PauseReasonSnapshot pauseReason,
        WorkflowFailureSnapshot latestFailure,
        ToolOutcomeSummary latestToolOutcome,
        List<String> resourceAnchors,
        List<NodeExecutionDto> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        List<ModelHitSnapshot> modelHits,
        List<ResumeInterventionDto> resumeInterventions,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState,
        AgentTurnState agentTurnState
    ) {
    }

    public record NodeExecutionDto(
        String id,
        String workflowInstanceId,
        String nodeKey,
        String nodeName,
        NodeStatus status,
        String detail,
        Instant updatedAt
    ) {
    }

    public enum ResumeInterventionStatus {
        PENDING,
        APPLIED,
        FAILED
    }

    public record ResumeInterventionDto(
        String id,
        String workflowInstanceId,
        String type,
        String source,
        String userId,
        String comment,
        Map<String, String> attributes,
        ResumeInterventionStatus status,
        Instant createdAt,
        Instant appliedAt,
        String failureReason
    ) {
    }

    public record ConversationMessageDto(
        String id,
        String sessionId,
        String role,
        String senderType,
        String senderId,
        String senderName,
        ConversationPayloadType payloadType,
        Map<String, Object> payload,
        @JsonIgnore String content,
        Instant createdAt,
        String taskId,
        String workflowInstanceId
    ) {
        public ConversationMessageDto {
            payload = immutableObjectMap(payload);
        }
    }

    public record ConversationSessionDto(
        String id,
        String scenarioId,
        String title,
        String customerId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessageDto> messages,
        String latestTaskId,
        String latestWorkflowInstanceId,
        ToolOutcomeSummary latestToolOutcome,
        ResumeTaskSnapshot latestResumeTask,
        PauseReasonSnapshot latestPauseReason,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
    }

    public record ExternalInteractionResultDto(
        ExternalInteractionOutcome outcome,
        String code,
        String summary,
        String rawProviderStatus,
        Map<String, Object> attributes
    ) {
        public ExternalInteractionResultDto {
            attributes = immutableObjectMap(attributes);
        }

        static ExternalInteractionResultDto fromContract(ExternalInteractionResult result) {
            if (result == null) {
                return null;
            }
            return new ExternalInteractionResultDto(
                result.outcome(),
                result.code(),
                result.summary(),
                result.rawProviderStatus(),
                result.attributes()
            );
        }

        ExternalInteractionResult toContract() {
            return new ExternalInteractionResult(outcome, code, summary, rawProviderStatus, attributes);
        }
    }

    public record ExternalInteractionEventDto(
        String id,
        String interactionTaskId,
        ExternalInteractionEventType eventType,
        ExternalInteractionEventSource eventSource,
        String dedupeKey,
        Map<String, Object> payload,
        ExternalInteractionResultDto result,
        Instant createdAt
    ) {
        public ExternalInteractionEventDto {
            payload = immutableObjectMap(payload);
        }

        static ExternalInteractionEventDto fromContract(ExternalInteractionEvent event) {
            if (event == null) {
                return null;
            }
            return new ExternalInteractionEventDto(
                event.id(),
                event.interactionTaskId(),
                event.eventType(),
                event.eventSource(),
                event.dedupeKey(),
                event.payload(),
                ExternalInteractionResultDto.fromContract(event.result()),
                event.createdAt()
            );
        }

        ExternalInteractionEvent toContract() {
            return new ExternalInteractionEvent(
                id,
                interactionTaskId,
                eventType,
                eventSource,
                dedupeKey,
                payload,
                result == null ? null : result.toContract(),
                createdAt
            );
        }
    }

    public record ExternalInteractionTaskDto(
        String id,
        ExternalInteractionType type,
        ExternalInteractionStatus status,
        String sessionId,
        String taskId,
        String workflowInstanceId,
        String messageId,
        String title,
        String instruction,
        String provider,
        String providerReference,
        String launchUrl,
        String returnToken,
        String returnPath,
        Instant expiresAt,
        ExternalInteractionResultDto latestResult,
        ExternalInteractionEventSource lastEventSource,
        Instant resumedAt,
        Instant createdAt,
        Instant updatedAt,
        List<ExternalInteractionEventDto> events
    ) {
        public ExternalInteractionTaskDto {
            events = events == null ? List.of() : List.copyOf(events);
        }

        static ExternalInteractionTaskDto fromContract(ExternalInteractionTask task, List<ExternalInteractionEvent> events) {
            return new ExternalInteractionTaskDto(
                task.id(),
                task.type(),
                task.status(),
                task.sessionId(),
                task.taskId(),
                task.workflowInstanceId(),
                task.messageId(),
                task.title(),
                task.instruction(),
                task.provider(),
                task.providerReference(),
                task.launchUrl(),
                task.returnToken(),
                task.returnPath(),
                task.expiresAt(),
                ExternalInteractionResultDto.fromContract(task.latestResult()),
                task.lastEventSource(),
                task.resumedAt(),
                task.createdAt(),
                task.updatedAt(),
                events == null ? List.of() : events.stream().map(ExternalInteractionEventDto::fromContract).toList()
            );
        }

        ExternalInteractionTask toContract() {
            return new ExternalInteractionTask(
                id,
                type,
                status,
                sessionId,
                taskId,
                workflowInstanceId,
                messageId,
                title,
                instruction,
                provider,
                providerReference,
                launchUrl,
                returnToken,
                returnPath,
                expiresAt,
                latestResult == null ? null : latestResult.toContract(),
                lastEventSource,
                resumedAt,
                createdAt,
                updatedAt
            );
        }
    }
}
