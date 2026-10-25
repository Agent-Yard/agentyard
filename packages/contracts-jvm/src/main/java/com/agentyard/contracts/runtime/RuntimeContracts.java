package com.agentyard.contracts.runtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable capability definitions and execution values shared by the control plane and Worker. */
public final class RuntimeContracts {
    private RuntimeContracts() {}

    private static <T> Map<String,T> immutableMap(Map<String,T> source) {
        return source == null || source.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum MessageBlockType {
        TEXT,
        IMAGE,
        RICH_TEXT,
        CARD
    }

    public enum LlmUsageSourceType {
        AGENT_MODEL,
        PRIVACY_MODEL
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

    public enum PlaybookResumeSource {
        HUMAN,
        EXTERNAL_SYSTEM
    }

    public record PlaybookExecutionPolicy(
        String timeoutPolicy,
        String retryPolicy
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
            config = immutableMap(config);
            if (operationMappings == null || operationMappings.isEmpty()) {
                operationMappings = Map.of();
            } else {
                Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
                operationMappings.forEach((key, value) -> copy.put(key, immutableMap(value)));
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
            config = immutableMap(config);
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

    public record MessageContent(
        List<Object> blocks,
        Map<String, Object> metadata
    ) {
        public MessageContent {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = immutableMap(metadata);
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
            sanitizeCountByChannel = immutableMap(sanitizeCountByChannel);
            restoreCountByChannel = immutableMap(restoreCountByChannel);
            entityTypeBreakdown = immutableMap(entityTypeBreakdown);
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
            rawUsage = immutableMap(rawUsage);
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

    public record PlaybookToolTaskResult(
        Map<String, Object> statePatch,
        String routeKey,
        PlaybookRunStatus terminalStatus,
        String failureReason
    ) {
        public PlaybookToolTaskResult {
            statePatch = immutableMap(statePatch);
        }
    }
}
