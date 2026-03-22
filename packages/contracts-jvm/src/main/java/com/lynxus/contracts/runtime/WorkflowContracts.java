package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.List;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    public enum ResourceType {
        SKILL,
        MCP,
        KNOWLEDGE_BASE
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
        WAITING_HUMAN
    }

    public record WorkflowStartRequest(
        String taskId,
        String workflowInstanceId,
        String scenarioId,
        String question,
        String operatorId
    ) {
    }

    public record WorkflowResult(
        String workflowInstanceId,
        WorkflowStatus status,
        String summary,
        List<NodeSnapshot> nodes,
        boolean escalationRequired
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
}
