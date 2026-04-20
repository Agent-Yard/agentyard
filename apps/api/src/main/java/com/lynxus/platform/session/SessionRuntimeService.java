package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookEdge;
import com.lynxus.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.HttpToolProviderDescriptor;
import com.lynxus.contracts.session.SessionContracts.KnowledgeBindingDescriptor;
import com.lynxus.contracts.session.SessionContracts.LlmModelDescriptor;
import com.lynxus.contracts.session.SessionContracts.McpToolProviderDescriptor;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionOwnerPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.SkillDescriptor;
import com.lynxus.contracts.session.SessionContracts.ToolDescriptor;
import com.lynxus.contracts.session.SessionContracts.ToolOperationDescriptor;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.contracts.session.SessionContracts.AssistantSessionConfig;
import com.lynxus.platform.catalog.CatalogDtos.AssistantDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseAgentDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.HttpToolProviderConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.KnowledgeBindingSnapshotDto;
import com.lynxus.platform.catalog.CatalogDtos.LlmModelConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.McpToolProviderConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.SkillConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.ToolConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.ToolOperationDto;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.shared.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@Service
public class SessionRuntimeService {
    private final SessionWorkflowGateway sessionWorkflowGateway;
    private final CatalogService catalogService;
    private final SessionRuntimeRepository repository;
    private final SessionDispatchLockService dispatchLockService;

    public SessionRuntimeService(
        SessionWorkflowGateway sessionWorkflowGateway,
        CatalogService catalogService,
        SessionRuntimeRepository repository,
        SessionDispatchLockService dispatchLockService
    ) {
        this.sessionWorkflowGateway = sessionWorkflowGateway;
        this.catalogService = catalogService;
        this.repository = repository;
        this.dispatchLockService = dispatchLockService;
    }

    public List<SessionRuntimeSessionDto> listSessions() {
        return repository.listSessions().stream()
            .map(this::markEndedIfWorkflowClosed)
            .toList();
    }

    public SessionRuntimeDetailDto getSessionDetail(String sessionId) {
        SessionRuntimeSessionDto session = repository.findSession(sessionId)
            .map(this::markEndedIfWorkflowClosed)
            .orElseThrow();
        return new SessionRuntimeDetailDto(
            session,
            repository.listEvents(sessionId),
            repository.listPlaybookRuns(sessionId)
        );
    }

    public SessionRuntimeSessionDto createSession(CreateSessionRequest request) {
        return dispatchLockService.withConversationLock(
            request.customerId(),
            request.assistantId(),
            () -> createOrReuseSession(request)
        );
    }

    public SessionRuntimeSessionDto sendMessage(String sessionId, SendSessionMessageRequest request) {
        return dispatchLockService.withSessionLock(sessionId, () -> sendMessageInternal(sessionId, request, null));
    }

    public SessionRuntimeSessionDto humanResume(String sessionId, HumanResumeRequest request) {
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        requirePlaybookRunInSession(sessionId, request.playbookRunId());
        sessionWorkflowGateway.humanResume(
            sessionId,
            new HumanResumeSignal(sessionId, request.playbookRunId(), request.payload())
        );
        return awaitPersistedSession(sessionId, existing);
    }

    public SessionRuntimeSessionDto externalCallback(String sessionId, ExternalCallbackRequest request) {
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        requirePlaybookRunInSession(sessionId, request.playbookRunId());
        sessionWorkflowGateway.externalCallback(
            sessionId,
            new ExternalCallbackSignal(sessionId, request.playbookRunId(), request.payload())
        );
        return awaitPersistedSession(sessionId, existing);
    }

    public SessionRuntimeSessionDto endHumanHandoff(String sessionId) {
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        sessionWorkflowGateway.endHumanHandoff(sessionId);
        return awaitPersistedSession(sessionId, existing);
    }

    public SessionRuntimeSessionDto humanOperatorReply(String sessionId, HumanOperatorReplyRequest request) {
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        sessionWorkflowGateway.humanOperatorReply(
            sessionId,
            new HumanOperatorReplySignal(sessionId, request.operatorId(), request.message(), request.payload())
        );
        return awaitPersistedSession(sessionId, existing);
    }

    private SessionStartRequest buildStartRequest(
        SessionRuntimeSessionDto session,
        AssistantDto assistant,
        AssistantReleaseDto release
    ) {
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId = indexResourcesByVersionId(release);
        List<AgentConfig> agents = release.agents().stream()
            .map(agent -> toAgentConfig(agent, release, resourcesByVersionId))
            .toList();
        return new SessionStartRequest(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            new AssistantSessionConfig(
                release.assistantId(),
                assistant.name(),
                release.releaseVersion(),
                requirePrimaryAgentId(release),
                new SessionOwnerPolicy(release.ownerPolicy().maxOwnerSwitchesPerTurn()),
                new SessionPolicy(
                    parseDuration(release.sessionPolicy().idleTimeout(), Duration.ofMinutes(30)),
                    parseDuration(release.sessionPolicy().maxWorkflowAge(), Duration.ofDays(7)),
                    release.sessionPolicy().maxWorkflowHistoryEvents()
                ),
                new PlaybookExecutionPolicy(
                    release.playbookPolicy().timeoutPolicy(),
                    release.playbookPolicy().retryPolicy()
                )
            ),
            agents,
            release.playbooks() == null ? List.of() : release.playbooks().stream().map(this::toPlaybookConfig).toList(),
            Map.of()
        );
    }

    private AgentConfig toAgentConfig(
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
                .map(this::toSkillDescriptor)
                .toList(),
            agent.toolResourceVersionIds().stream()
                .map(resourcesByVersionId::get)
                .filter(item -> item != null && item.configuration() != null && item.configuration().tool() != null)
                .map(this::toToolDescriptor)
                .toList()
        );
    }

    private Map<String, AssistantReleaseResourceDto> indexResourcesByVersionId(AssistantReleaseDto release) {
        Map<String, AssistantReleaseResourceDto> resourcesByVersionId = new LinkedHashMap<>();
        if (release.resources() == null) {
            return resourcesByVersionId;
        }
        release.resources().forEach(resource -> resourcesByVersionId.put(resource.resourceVersionId(), resource));
        return resourcesByVersionId;
    }

    private LlmModelDescriptor resolveModelDescriptor(
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
            config.maxTokens()
        );
    }

    private SkillDescriptor toSkillDescriptor(AssistantReleaseResourceDto resource) {
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

    private KnowledgeBindingDescriptor toKnowledgeBindingDescriptor(KnowledgeBindingSnapshotDto binding) {
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

    private ToolDescriptor toToolDescriptor(AssistantReleaseResourceDto resource) {
        ToolConfigDto tool = resource.configuration().tool();
        return new ToolDescriptor(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            tool.operations() == null ? List.of() : tool.operations().stream().map(this::toToolOperationDescriptor).toList(),
            tool.providerType() == null ? null : tool.providerType().name(),
            tool.authType(),
            tool.timeoutSeconds(),
            tool.retryPolicy(),
            toHttpToolProviderDescriptor(tool.http()),
            toMcpToolProviderDescriptor(tool.mcp())
        );
    }

    private ToolOperationDescriptor toToolOperationDescriptor(ToolOperationDto operation) {
        return new ToolOperationDescriptor(
            operation.name(),
            operation.description(),
            operation.inputSchema(),
            operation.outputSchema()
        );
    }

    private HttpToolProviderDescriptor toHttpToolProviderDescriptor(HttpToolProviderConfigDto http) {
        if (http == null) {
            return null;
        }
        return new HttpToolProviderDescriptor(http.endpoint(), http.method());
    }

    private McpToolProviderDescriptor toMcpToolProviderDescriptor(McpToolProviderConfigDto mcp) {
        if (mcp == null) {
            return null;
        }
        return new McpToolProviderDescriptor(
            mcp.serverName(),
            mcp.transport(),
            mcp.connectionUri(),
            mcp.namespace(),
            mcp.heartbeatSeconds(),
            mcp.operationMappings()
        );
    }

    private PlaybookConfig toPlaybookConfig(com.lynxus.platform.catalog.CatalogDtos.PlaybookDto playbook) {
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
                    node.config()
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

    private SessionRuntimeSessionDto createOrReuseSession(CreateSessionRequest request) {
        AssistantDto assistant = catalogService.getAssistant(request.assistantId());
        AssistantReleaseDto release = resolveAssistantRelease(assistant);
        String openingMessage = request.openingMessage() == null ? null : request.openingMessage().trim();
        Optional<SessionRuntimeSessionDto> activeSession = findReusableActiveSession(request.customerId(), assistant.id());
        if (activeSession.isPresent()) {
            SessionRuntimeSessionDto existing = activeSession.orElseThrow();
            if (openingMessage == null || openingMessage.isBlank()) {
                return existing;
            }
            return dispatchLockService.withSessionLock(
                existing.id(),
                () -> sendMessageInternal(existing.id(), new SendSessionMessageRequest(request.customerId(), openingMessage), existing)
            );
        }

        Instant now = Instant.now();
        String sessionId = nextId("session-v2");
        String title = summarizeTitle(openingMessage);
        SessionRuntimeSessionDto bootstrap = new SessionRuntimeSessionDto(
            sessionId,
            assistant.scenarioId(),
            title,
            request.customerId(),
            assistant.id(),
            assistant.name(),
            release.releaseVersion(),
            "IDLE",
            requirePrimaryAgentId(release),
            requirePrimaryAgentId(release),
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            now.plus(Duration.ofMinutes(30)),
            now,
            now,
            null,
            0
        );
        sessionWorkflowGateway.start(buildStartRequest(bootstrap, assistant, release));
        SessionRuntimeSessionDto updated = awaitPersistedSession(bootstrap.id(), bootstrap);
        if (openingMessage == null || openingMessage.isBlank()) {
            return updated;
        }
        return dispatchLockService.withSessionLock(
            updated.id(),
            () -> sendMessageInternal(updated.id(), new SendSessionMessageRequest(request.customerId(), openingMessage), updated)
        );
    }

    private Optional<SessionRuntimeSessionDto> findReusableActiveSession(String customerId, String assistantId) {
        return repository.findActiveSession(customerId, assistantId)
            .map(this::markEndedIfWorkflowClosed)
            .filter(session -> !"ENDED".equals(session.status()));
    }

    private SessionRuntimeSessionDto sendMessageInternal(
        String sessionId,
        SendSessionMessageRequest request,
        SessionRuntimeSessionDto existingSession
    ) {
        SessionRuntimeSessionDto existing = existingSession == null
            ? repository.findSession(sessionId).orElseThrow()
            : existingSession;
        SessionRuntimeSessionDto current = markEndedIfWorkflowClosed(existing);
        if ("ENDED".equals(current.status())) {
            throw new ConflictException("session has ended");
        }
        try {
            SessionUserMessageUpdateResult result = sessionWorkflowGateway.submitUserMessage(
                sessionId,
                new UserMessage(nextId("session-event"), request.customerId(), request.message(), Map.of("text", request.message()))
            );
            if (result.status() == SessionMessageDeliveryStatus.BUSY) {
                throw new ConflictException("session is busy");
            }
            if (result.status() == SessionMessageDeliveryStatus.REJECTED) {
                throw new ConflictException(result.reason() == null ? "session rejected message" : result.reason());
            }
            return awaitPersistedSession(sessionId, current);
        } catch (RuntimeException error) {
            if (!sessionWorkflowGateway.isWorkflowOpen(sessionId)) {
                markEnded(current, Instant.now());
                throw new ConflictException("session has ended");
            }
            throw error;
        }
    }

    private SessionRuntimeSessionDto markEndedIfWorkflowClosed(SessionRuntimeSessionDto existing) {
        if ("ENDED".equals(existing.status())) {
            return existing;
        }
        if (!sessionWorkflowGateway.isWorkflowOpen(existing.id())) {
            return markEnded(existing, Instant.now());
        }
        return existing;
    }

    private SessionRuntimeSessionDto markEnded(SessionRuntimeSessionDto existing, Instant now) {
        SessionRuntimeSessionDto ended = new SessionRuntimeSessionDto(
            existing.id(),
            existing.scenarioId(),
            existing.title(),
            existing.customerId(),
            existing.assistantId(),
            existing.assistantName(),
            existing.assistantReleaseVersion(),
            "ENDED",
            existing.primaryAgentId(),
            existing.currentOwnerAgentId(),
            existing.activePlaybookRunId(),
            false,
            existing.sessionHumanHandoffActive(),
            false,
            existing.draining(),
            existing.sharedState(),
            null,
            existing.createdAt(),
            now,
            existing.endedAt() == null ? now : existing.endedAt(),
            existing.latestEventSequence()
        );
        repository.saveSession(ended);
        return ended;
    }

    private SessionRuntimeSessionDto awaitPersistedSession(String sessionId, SessionRuntimeSessionDto fallback) {
        for (int attempt = 0; attempt < 20; attempt += 1) {
            Optional<SessionRuntimeSessionDto> persisted = repository.findSession(sessionId);
            if (persisted.isPresent()) {
                return markEndedIfWorkflowClosed(persisted.orElseThrow());
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return markEndedIfWorkflowClosed(fallback);
    }

    private void requirePlaybookRunInSession(String sessionId, String playbookRunId) {
        boolean owned = repository.listPlaybookRuns(sessionId).stream()
            .anyMatch(run -> run.runId().equals(playbookRunId));
        if (!owned) {
            throw new ConflictException("playbook run does not belong to session");
        }
    }

    private AssistantReleaseDto resolveAssistantRelease(AssistantDto assistant) {
        if (assistant.currentRelease() != null) {
            return assistant.currentRelease();
        }
        throw new IllegalStateException("assistant must have a published release before session runtime can start");
    }

    private static String requirePrimaryAgentId(AssistantReleaseDto release) {
        if (release.primaryAgentId() == null || release.primaryAgentId().isBlank()) {
            throw new IllegalStateException("assistant release primaryAgentId is required");
        }
        return release.primaryAgentId();
    }

    private static Duration parseDuration(String rawValue, Duration fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return Duration.parse(rawValue);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static String summarizeTitle(String openingMessage) {
        if (openingMessage == null || openingMessage.isBlank()) {
            return "新会话";
        }
        return openingMessage.length() <= 24 ? openingMessage : openingMessage.substring(0, 24);
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
