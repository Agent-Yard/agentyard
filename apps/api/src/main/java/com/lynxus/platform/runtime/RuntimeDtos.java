package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolInvocationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeDtos {
    private RuntimeDtos() {
    }

    public record TaskLaunchRequest(String scenarioId, String assistantId, String question, String requester) {
    }

    public record HumanActionRequest(String action, String comment, String operatorId, Map<String, String> attributes) {
    }

    public record CreateConversationSessionRequest(String scenarioId, String assistantId, String requester, String openingMessage) {
    }

    public record ConversationMessageRequest(String requester, String message) {
    }

    public record TaskInstanceDto(
        String id,
        String scenarioId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        String question,
        String requester,
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
        WorkflowStatus status,
        String summary,
        String finalReply,
        String currentNodeKey,
        boolean escalationRequired,
        ExecutionCheckpoint checkpoint,
        HumanTaskSnapshot humanTask,
        ToolOutcomeSummary latestToolOutcome,
        List<String> resourceAnchors,
        List<NodeExecutionDto> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        List<HumanInterventionDto> interventions
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

    public record HumanInterventionDto(
        String id,
        String workflowInstanceId,
        String action,
        String operator,
        String comment,
        Instant createdAt
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
        String requester,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessageDto> messages,
        String latestTaskId,
        String latestWorkflowInstanceId,
        ToolOutcomeSummary latestToolOutcome,
        HumanTaskSnapshot latestHumanTask
    ) {
    }
}
