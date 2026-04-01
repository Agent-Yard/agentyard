package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
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
import java.util.List;
import java.util.Map;

public final class RuntimeDtos {
    private RuntimeDtos() {
    }

    public record TaskLaunchRequest(String scenarioId, String assistantId, String question, String customerId) {
    }

    public record ResumeActionRequest(String type, String comment, String userId, Map<String, String> attributes) {
    }

    public record CreateConversationSessionRequest(String scenarioId, String assistantId, String customerId, String openingMessage) {
    }

    public record ConversationMessageRequest(String customerId, String message) {
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
        String content,
        Instant createdAt,
        String taskId,
        String workflowInstanceId
    ) {
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
