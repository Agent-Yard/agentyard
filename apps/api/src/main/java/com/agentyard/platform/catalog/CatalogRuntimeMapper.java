package com.agentyard.platform.catalog;

import com.agentyard.contracts.runtime.RuntimeContracts.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import java.util.*;

/** Pure mapping of independently resolved immutable versions into execution configuration. */
public final class CatalogRuntimeMapper {
    private CatalogRuntimeMapper() {}
    public record ResolvedResource(String resourceId, String resourceName, String resourceVersionId,
        String resourceVersion, ResourceVersionConfigurationDto configuration) {}
    private static final List<String> DEFAULT_RETRYABLE_REMOTE_CATEGORIES = List.of("REMOTE_TIMEOUT", "REMOTE_UNAVAILABLE", "REMOTE_RATE_LIMITED", "UNKNOWN");
    public static LlmModelDescriptor toModelDescriptor(ResolvedResource resource) {
        var config = resource.configuration().llmModel();
        return new LlmModelDescriptor(resource.resourceId(), resource.resourceName(), resource.resourceVersionId(), resource.resourceVersion(),
            config.providerType(), config.modelId(), config.baseUrl(), config.apiKeyEnvVar(), config.temperature(), config.maxTokens(),
            config.privateDeployment(), config.enableThinking(), config.reasoningEffort());
    }
    public static SkillDescriptor toSkillDescriptor(ResolvedResource resource) {
        SkillConfigDto skill = resource.configuration().skill();
        return new SkillDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            skill.skillName(),
            skill.skillDesc(),
            skill.skillPrompt()
        );
    }

    public static KnowledgeBindingDescriptor toKnowledgeBindingDescriptor(KnowledgeBindingSnapshotDto binding) {
        if (binding == null) {
            return null;
        }
        return new KnowledgeBindingDescriptor(
            binding.knowledgeBaseId(),
            binding.knowledgeBaseName(),
            binding.knowledgeReleaseId(),
            binding.knowledgeReleaseVersion(),
            binding.snapshotId(),
            binding.defaultTopK(),
            binding.retrievalMode(),
            binding.minScore()
        );
    }

    public static ToolDescriptor toToolDescriptor(ResolvedResource resource) {
        ToolConfigDto tool = resource.configuration().tool();
        return new ToolDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            tool.operations() == null ? List.of() : tool.operations().stream().map(CatalogRuntimeMapper::toToolOperationDescriptor).toList(),
            toToolConnectorDescriptor(tool.connector())
        );
    }

    private static ToolOperationDescriptor toToolOperationDescriptor(ToolOperationDto operation) {
        return new ToolOperationDescriptor(
            operation.name(),
            operation.description(),
            operation.inputSchema(),
            operation.outputSchema()
        );
    }

    private static ToolConnectorDescriptor toToolConnectorDescriptor(ToolConnectorConfigDto connector) {
        if (connector == null) {
            return null;
        }
        return new ToolConnectorDescriptor(
            connector.connectorType(),
            toToolConnectorAccountSnapshot(connector.accountSnapshot()),
            connector.timeoutSeconds(),
            toToolConnectorRuntimeRetryPolicy(connector.retryPolicy()),
            connector.config(),
            connector.operationMappings()
        );
    }

    private static ToolConnectorRuntimeRetryPolicy toToolConnectorRuntimeRetryPolicy(String retryPolicyPreset) {
        String preset = retryPolicyPreset == null ? "" : retryPolicyPreset.trim();
        if (preset.isEmpty() || "NONE".equals(preset)) {
            return new ToolConnectorRuntimeRetryPolicy(
                ToolConnectorRetryMode.NONE,
                1,
                0,
                0,
                1.0,
                List.of(),
                List.of()
            );
        }
        if ("FIXED".equals(preset)) {
            return new ToolConnectorRuntimeRetryPolicy(
                ToolConnectorRetryMode.FIXED,
                3,
                100,
                100,
                1.0,
                DEFAULT_RETRYABLE_REMOTE_CATEGORIES,
                List.of()
            );
        }
        if ("EXPONENTIAL".equals(preset) || "EXPONENTIAL_BACKOFF".equals(preset)) {
            return new ToolConnectorRuntimeRetryPolicy(
                ToolConnectorRetryMode.EXPONENTIAL,
                3,
                100,
                1000,
                2.0,
                DEFAULT_RETRYABLE_REMOTE_CATEGORIES,
                List.of()
            );
        }
        throw new IllegalArgumentException("unsupported tool connector retryPolicy preset: " + retryPolicyPreset);
    }

    private static ToolConnectorAccountSnapshot toToolConnectorAccountSnapshot(com.agentyard.platform.catalog.CatalogDtos.ToolConnectorAccountSnapshotDto accountSnapshot) {
        if (accountSnapshot == null) {
            return null;
        }
        return new ToolConnectorAccountSnapshot(accountSnapshot.accountId(), accountSnapshot.runtimeSecretRef());
    }

    public static PlaybookConfig toPlaybookConfig(PlaybookDto metadata, PlaybookVersionDto version) {
        var playbook = version.definition();
        return new PlaybookConfig(
            version.id(),
            metadata.name(),
            playbook.description(),
            playbook.inputSchema(),
            playbook.resultSchema(),
            new PlaybookExecutionPolicy(
                playbook.executionPolicy() == null ? null : playbook.executionPolicy().timeoutPolicy(),
                playbook.executionPolicy() == null ? null : playbook.executionPolicy().retryPolicy()
            ),
            playbook.allowHumanTask(),
            playbook.allowExternalInteraction(),
            playbook.entryNodeKey(),
            playbook.nodes() == null ? List.of() : playbook.nodes().stream()
                .map(node -> new PlaybookNode(
                    node.nodeKey(),
                    node.nodeName(),
                    node.nodeType(),
                    node.description(),
                    node.scriptRef(),
                    node.scriptVersion(),
                    node.toolId(),
                    node.toolOperation(),
                    node.config(),
                    new com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeLayout(
                        node.layout().x(),
                        node.layout().y()
                    )
                ))
                .toList(),
            playbook.edges() == null ? List.of() : playbook.edges().stream()
                .map(edge -> new PlaybookEdge(
                    edge.edgeKey(),
                    edge.sourceNodeKey(),
                    edge.targetNodeKey(),
                    edge.routeKey(),
                    edge.label(),
                    edge.defaultEdge()
                ))
                .toList()
        );
    }

}
