package com.agentyard.platform.catalog;

import com.agentyard.contracts.session.SessionContracts.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import java.util.*;

/** Converts immutable releases for both session and task execution. */
public final class ReleaseRuntimeMapper {
    private ReleaseRuntimeMapper() {}
    private static final List<String> DEFAULT_RETRYABLE_REMOTE_CATEGORIES = List.of(
        "REMOTE_TIMEOUT",
        "REMOTE_UNAVAILABLE",
        "REMOTE_RATE_LIMITED",
        "UNKNOWN"
    );
    public static AgentConfig toAgentConfig(
        AssistantReleaseAgentDto agent,
        AssistantReleaseDto release,
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId
    ) {
        return new AgentConfig(
            agent.agentId(),
            agent.name(),
            agent.role(),
            agent.responsibility(),
            resolveModelDescriptor(agent, release, resourcesByVersionId),
            resolvePrivacyModelDescriptor(agent, release, resourcesByVersionId),
            agent.effectivePrivacyMappingEnabled(),
            agent.executionPolicy().systemPrompt(),
            agent.executionPolicy().knowledgeEnabled(),
            agent.executionPolicy().knowledgeBaseId(),
            toKnowledgeBindingDescriptor(agent.knowledgeBinding()),
            agent.executionPolicy().memoryWindowSize(),
            agent.canOwnSession(),
            agent.allowedActions(),
            agent.switchableOwnerAgentIds(),
            agent.playbookIds(),
            agent.skillResourceVersionIds().stream()
                .map(resourcesByVersionId::get)
                .filter(item -> item != null && item.configuration() != null && item.configuration().skill() != null)
                .map(ReleaseRuntimeMapper::toSkillDescriptor)
                .toList(),
            agent.toolResourceVersionIds().stream()
                .map(resourcesByVersionId::get)
                .filter(item -> item != null && item.configuration() != null && item.configuration().tool() != null)
                .map(ReleaseRuntimeMapper::toToolDescriptor)
                .toList()
        );
    }

    public static Map<String, AssistantReleaseResourceDto> indexResourcesByVersionId(AssistantReleaseDto release) {
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId = new LinkedHashMap<>();
        if (release.resources() == null) {
            return resourcesByVersionId;
        }
        release.resources().forEach(resource -> resourcesByVersionId.put(resource.resourceVersionId(), resource));
        return resourcesByVersionId;
    }

    private static LlmModelDescriptor resolveModelDescriptor(
        AssistantReleaseAgentDto agent,
        AssistantReleaseDto release,
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId
    ) {
        AssistantReleaseResourceDto resource = null;
        List<AssistantReleaseResourceDto> releaseResources = release.resources() == null ? List.of() : release.resources();
        if (agent.executionPolicy().modelResourceId() != null && !agent.executionPolicy().modelResourceId().isBlank()) {
            resource = releaseResources.stream()
                .filter(item -> agent.executionPolicy().modelResourceId().equals(item.resourceId()))
                .findFirst()
                .orElse(null);
        }
        if (resource == null && release.defaultModelBinding() != null) {
            resource = resourcesByVersionId.get(release.defaultModelBinding().resourceVersionId());
        }
        if (resource == null || resource.configuration() == null || resource.configuration().llmModel() == null) {
            return null;
        }
        LlmModelConfigDto config = resource.configuration().llmModel();
        return new LlmModelDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            config.providerType(),
            config.modelId(),
            config.baseUrl(),
            config.apiKeyEnvVar(),
            config.temperature(),
            config.maxTokens(),
            config.privateDeployment(),
            effectiveEnableThinking(release, config),
            effectiveReasoningEffort(release, config)
        );
    }

    private static LlmModelDescriptor resolvePrivacyModelDescriptor(
        AssistantReleaseAgentDto agent,
        AssistantReleaseDto release,
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId
    ) {
        if (!agent.effectivePrivacyMappingEnabled() || agent.effectivePrivacyModelBinding() == null) {
            return null;
        }
        AssistantReleaseResourceDto resource = resourcesByVersionId.get(agent.effectivePrivacyModelBinding().resourceVersionId());
        if (resource == null || resource.configuration() == null || resource.configuration().llmModel() == null) {
            return null;
        }
        LlmModelConfigDto config = resource.configuration().llmModel();
        return new LlmModelDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            config.providerType(),
            config.modelId(),
            config.baseUrl(),
            config.apiKeyEnvVar(),
            config.temperature(),
            config.maxTokens(),
            config.privateDeployment(),
            config.enableThinking(),
            config.reasoningEffort()
        );
    }

    private static Boolean effectiveEnableThinking(AssistantReleaseDto release, LlmModelConfigDto config) {
        if (release.modelPolicy() != null && release.modelPolicy().enableThinking() != null) {
            return release.modelPolicy().enableThinking();
        }
        return config.enableThinking();
    }

    private static String effectiveReasoningEffort(AssistantReleaseDto release, LlmModelConfigDto config) {
        if (release.modelPolicy() != null && release.modelPolicy().reasoningEffort() != null && !release.modelPolicy().reasoningEffort().isBlank()) {
            return release.modelPolicy().reasoningEffort();
        }
        return config.reasoningEffort();
    }

    private static SkillDescriptor toSkillDescriptor(AssistantReleaseResourceDto resource) {
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

    private static KnowledgeBindingDescriptor toKnowledgeBindingDescriptor(KnowledgeBindingSnapshotDto binding) {
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

    private static ToolDescriptor toToolDescriptor(AssistantReleaseResourceDto resource) {
        ToolConfigDto tool = resource.configuration().tool();
        return new ToolDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            tool.operations() == null ? List.of() : tool.operations().stream().map(ReleaseRuntimeMapper::toToolOperationDescriptor).toList(),
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

    public static PlaybookConfig toPlaybookConfig(com.agentyard.platform.catalog.CatalogDtos.PlaybookDto playbook) {
        return new PlaybookConfig(
            playbook.id(),
            playbook.name(),
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
                    new com.agentyard.contracts.session.SessionContracts.PlaybookNodeLayout(
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
