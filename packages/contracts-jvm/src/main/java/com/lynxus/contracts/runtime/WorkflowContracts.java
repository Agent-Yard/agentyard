package com.lynxus.contracts.runtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowContracts {
    private WorkflowContracts() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum ResourceType {
        TOOL,
        LLM_MODEL,
        SKILL
    }

    public enum ToolProviderType {
        HTTP,
        MCP
    }

    public enum ToolKind {
        RESOURCE,
        BUILTIN
    }

    public enum ShareScope {
        PRIVATE,
        DOMAIN_SHARED
    }

    public enum VersionStatus {
        DRAFT,
        PUBLISHED
    }

    public record ToolOutcomeSummary(
        String callId,
        String toolId,
        String toolName,
        ToolKind toolKind,
        String operation,
        String providerType,
        String resourceId,
        String resourceName,
        Map<String, Object> result
    ) {
        public ToolOutcomeSummary {
            result = immutableObjectMap(result);
        }
    }

    public record LogContext(
        String traceId,
        String sessionId,
        String workflowId,
        String customerId,
        String userId
    ) {
    }

    public record KnowledgeImportRequest(
        String workflowId,
        String knowledgeBaseId,
        String importJobId,
        LogContext logContext
    ) {
    }

    public record KnowledgeIndexBuildRequest(
        String workflowId,
        String knowledgeBaseId,
        String indexSnapshotId,
        LogContext logContext
    ) {
    }

    public record KnowledgeJobResult(
        String workflowId,
        String knowledgeBaseId,
        String jobId,
        String status,
        String summary,
        Instant completedAt
    ) {
    }
}
