package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.List;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    public enum ResourceType {
        SKILL,
        MCP,
        KNOWLEDGE_BASE,
        LLM_MODEL,
        PROMPT_TEMPLATE
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
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        List<String> resourceAnchors,
        String question,
        String requester,
        String operatorId,
        String assistantConfigJson,
        String graphSpecJson,
        String sessionContextJson
    ) {
    }

    public record McpInvocationSummary(
        String capabilityName,
        String externalTicketId,
        String status,
        String recommendedAction,
        String detail
    ) {
    }

    public record WorkflowResult(
        String workflowInstanceId,
        WorkflowStatus status,
        String summary,
        List<NodeSnapshot> nodes,
        boolean escalationRequired,
        McpInvocationSummary mcpSummary
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
