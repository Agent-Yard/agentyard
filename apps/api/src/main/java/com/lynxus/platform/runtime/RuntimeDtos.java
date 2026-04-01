package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
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
}
