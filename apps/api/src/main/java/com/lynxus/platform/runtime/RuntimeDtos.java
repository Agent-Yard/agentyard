package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.List;

public final class RuntimeDtos {
    private RuntimeDtos() {
    }

    public record TaskLaunchRequest(String scenarioId, String question, String requester) {
    }

    public record HumanActionRequest(String action, String comment) {
    }

    public record TaskInstanceDto(
        String id,
        String scenarioId,
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
        WorkflowStatus status,
        String summary,
        boolean escalationRequired,
        List<NodeExecutionDto> nodes,
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
}
