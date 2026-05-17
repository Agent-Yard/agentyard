package com.lynxus.platform.session;

import com.lynxus.platform.auth.CurrentUserResolver;
import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookEdge;
import com.lynxus.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.EndHumanHandoffSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.lynxus.contracts.session.SessionContracts.ChannelIdentityImportTarget;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnMessage;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.ExistingSessionImportTarget;
import com.lynxus.contracts.session.SessionContracts.ImportSessionTarget;
import com.lynxus.contracts.session.SessionContracts.KnowledgeBindingDescriptor;
import com.lynxus.contracts.session.SessionContracts.LlmModelDescriptor;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.contracts.session.SessionContracts.SessionOwnerPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionTriggerType;
import com.lynxus.contracts.session.SessionContracts.SkillDescriptor;
import com.lynxus.contracts.session.SessionContracts.ToolDescriptor;
import com.lynxus.contracts.session.SessionContracts.ToolConnectorAccountSnapshot;
import com.lynxus.contracts.session.SessionContracts.ToolConnectorDescriptor;
import com.lynxus.contracts.session.SessionContracts.ToolConnectorRetryMode;
import com.lynxus.contracts.session.SessionContracts.ToolConnectorRuntimeRetryPolicy;
import com.lynxus.contracts.session.SessionContracts.ToolOperationDescriptor;
import com.lynxus.contracts.session.SessionContracts.TrustedImportSessionTurnMessage;
import com.lynxus.contracts.session.SessionContracts.TrustedImportSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.UserTurn;
import com.lynxus.contracts.session.SessionContracts.WebIdentityImportTarget;
import com.lynxus.contracts.session.SessionContracts.WebSessionTurnMessageInput;
import com.lynxus.contracts.session.SessionContracts.AssistantSessionConfig;
import com.lynxus.persistence.session.SessionRuntimeStore;
import com.lynxus.platform.catalog.CatalogDtos.AssistantDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseAgentDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.KnowledgeBindingSnapshotDto;
import com.lynxus.platform.catalog.CatalogDtos.LlmModelConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.SkillConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.ToolConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.ToolConnectorConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.ToolOperationDto;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.platform.shared.redis.RedisIdempotencyService;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisSharedStateMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@Service
public class SessionRuntimeService {
    private static final List<String> DEFAULT_RETRYABLE_REMOTE_CATEGORIES = List.of(
        "REMOTE_TIMEOUT",
        "REMOTE_UNAVAILABLE",
        "REMOTE_RATE_LIMITED",
        "UNKNOWN"
    );
    private static final String ENTRY_SCOPE_WEB = SessionRuntimeStore.SessionEntryScope.WEB.name();
    private static final String ENTRY_SCOPE_CHANNEL = SessionRuntimeStore.SessionEntryScope.CHANNEL.name();
    private static final String TURN_STATUS_ALLOCATED_IDS = SessionRuntimeStore.SessionRuntimeTurnStatus.ALLOCATED_IDS.name();
    private static final String TURN_STATUS_MESSAGES_APPENDED = SessionRuntimeStore.SessionRuntimeTurnStatus.MESSAGES_APPENDED.name();
    private static final String TURN_STATUS_WORKFLOW_ACCEPTED = SessionRuntimeStore.SessionRuntimeTurnStatus.WORKFLOW_ACCEPTED.name();

    private final SessionWorkflowGateway sessionWorkflowGateway;
    private final CatalogService catalogService;
    private final SessionRuntimeRepository repository;
    private final SessionDispatchLockService dispatchLockService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisKeyspace redisKeyspace;
    private final RedisIdempotencyService idempotencyService;
    private final SessionRuntimeChangeNoticePublisher changeNoticePublisher;
    private final ExternalCallbackIdempotencyKeyFactory externalCallbackIdempotencyKeyFactory;
    private final CurrentUserResolver currentUserResolver;

    public SessionRuntimeService(
        SessionWorkflowGateway sessionWorkflowGateway,
        CatalogService catalogService,
        SessionRuntimeRepository repository,
        SessionDispatchLockService dispatchLockService
    ) {
        this(
            sessionWorkflowGateway,
            catalogService,
            repository,
            dispatchLockService,
            new StringRedisTemplate(),
            new ObjectMapper(),
            new RedisKeyspace(),
            new RedisIdempotencyService(
                new StringRedisTemplate(),
                new RedisJsonCodec(new ObjectMapper()),
                new RedisSharedStateProperties(null, null, null, null, null, 0, null),
                new RedisSharedStateMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                )
            ),
            null,
            new ExternalCallbackIdempotencyKeyFactory(new ObjectMapper()),
            () -> {
                throw new IllegalStateException("current user resolver unavailable");
            }
        );
    }

    @Autowired
    public SessionRuntimeService(
        SessionWorkflowGateway sessionWorkflowGateway,
        CatalogService catalogService,
        SessionRuntimeRepository repository,
        SessionDispatchLockService dispatchLockService,
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        RedisKeyspace redisKeyspace,
        RedisIdempotencyService idempotencyService,
        SessionRuntimeChangeNoticePublisher changeNoticePublisher,
        ExternalCallbackIdempotencyKeyFactory externalCallbackIdempotencyKeyFactory,
        CurrentUserResolver currentUserResolver
    ) {
        this.sessionWorkflowGateway = sessionWorkflowGateway;
        this.catalogService = catalogService;
        this.repository = repository;
        this.dispatchLockService = dispatchLockService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisKeyspace = redisKeyspace;
        this.idempotencyService = idempotencyService;
        this.changeNoticePublisher = changeNoticePublisher;
        this.externalCallbackIdempotencyKeyFactory = externalCallbackIdempotencyKeyFactory;
        this.currentUserResolver = currentUserResolver;
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
            repository.listMessages(sessionId),
            repository.listEvents(sessionId),
            repository.listPlaybookRuns(sessionId)
        );
    }

    public PrivacyMappingSummaryDto getPrivacyMappingSummary(String sessionId) {
        String payload = redisTemplate.opsForValue().get(redisKeyspace.privacySessionSummary(sessionId));
        if (payload == null || payload.isBlank()) {
            return new PrivacyMappingSummaryDto(false, null, null, Map.of(), Map.of(), Map.of(), 0, 0, 0, null);
        }
        try {
            Map<String, Object> summary = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
            return new PrivacyMappingSummaryDto(
                Boolean.TRUE.equals(summary.get("enabled")),
                summary.get("privacyModelResourceId") == null ? null : String.valueOf(summary.get("privacyModelResourceId")),
                summary.get("privacyModelName") == null ? null : String.valueOf(summary.get("privacyModelName")),
                intMap(summary.get("sanitizeCountByChannel")),
                intMap(summary.get("restoreCountByChannel")),
                intMap(summary.get("entityTypeBreakdown")),
                intValue(summary.get("placeholderCount")),
                intValue(summary.get("unresolvedPlaceholderCount")),
                intValue(summary.get("blockedEventCount")),
                summary.get("lastProcessedAt") == null ? null : Instant.parse(String.valueOf(summary.get("lastProcessedAt")))
            );
        } catch (Exception error) { // noqa: BLE001
            throw new IllegalStateException("failed to read privacy mapping summary", error);
        }
    }

    public SendSessionTurnResponse sendTurn(SendSessionTurnRequest request, String idempotencyKey) {
        if (request == null) {
            throw new IllegalArgumentException("send turn request is required");
        }
        String turnDedupKey = requireMatchingIdempotencyKey(idempotencyKey, request.turnDedupKey(), "turnDedupKey");
        String customerId = requireText(request.customerId(), "customerId");
        List<TurnMessageInput> messages = webTurnMessages(customerId, request.messages());
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        SessionRuntimeSessionDto session = resolveWebTurnSession(request, customerId, messages.getFirst().message());
        return dispatchLockService.withSessionLock(
            session.id(),
            () -> acceptTurn(session, turnDedupKey, "USER_MESSAGE", messages, request.metadata())
        );
    }

    public SendSessionTurnResponse importTurn(TrustedImportSessionTurnRequest request, String idempotencyKey) {
        if (request == null) {
            throw new IllegalArgumentException("import turn request is required");
        }
        String turnDedupKey = requireMatchingIdempotencyKey(idempotencyKey, request.turnDedupKey(), "turnDedupKey");
        String sourceSystem = requireText(request.sourceSystem(), "sourceSystem");
        String importBatchId = requireText(request.importBatchId(), "importBatchId");
        List<TurnMessageInput> messages = trustedImportMessages(request.messages(), sourceSystem, importBatchId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        SessionRuntimeSessionDto session = resolveImportSession(request.target(), messages.getFirst().message());
        return dispatchLockService.withSessionLock(
            session.id(),
            () -> acceptTurn(session, turnDedupKey, "IMPORT_TURN", messages, request.metadata())
        );
    }

    public ChannelInboundSessionTurnResponse channelInboundTurn(
        ChannelInboundSessionTurnRequest request,
        String idempotencyKey
    ) {
        if (request == null) {
            throw new IllegalArgumentException("channel inbound turn request is required");
        }
        String turnDedupKey = requireMatchingIdempotencyKey(idempotencyKey, request.dedupKey(), "channelInbound.dedupKey");
        String channelProfileId = requireText(request.channelProfileId(), "channelInbound.channelProfileId");
        String externalConversationId = requireText(request.externalConversationId(), "channelInbound.externalConversationId");
        String customerId = requireText(request.customerId(), "channelInbound.customerId");
        String assistantId = requireText(request.assistantId(), "channelInbound.assistantId");
        List<TurnMessageInput> messages = channelTurnMessages(request, channelProfileId, externalConversationId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        SessionRuntimeSessionDto session = resolveChannelTurnSession(
            request.sessionId(),
            channelProfileId,
            externalConversationId,
            customerId,
            assistantId,
            messages.getFirst().message()
        );
        SendSessionTurnResponse response = dispatchLockService.withSessionLock(
            session.id(),
            () -> acceptTurn(session, turnDedupKey, "CHANNEL_INBOUND", messages, request.metadata())
        );
        return new ChannelInboundSessionTurnResponse(
            response.sessionId(),
            response.turnId(),
            response.status(),
            response.acceptedMessageIds(),
            response.acceptedMessageAllocations(),
            response.duplicateExternalMessageIds(),
            response.reason()
        );
    }

    private SendSessionTurnResponse acceptTurn(
        SessionRuntimeSessionDto session,
        String turnDedupKey,
        String triggerType,
        List<TurnMessageInput> inputs,
        Map<String, Object> metadata
    ) {
        Optional<SessionRuntimeStore.SessionRuntimeTurnData> existingTurn = repository.findTurnByDedupKey(session.id(), turnDedupKey);
        if (existingTurn == null) {
            existingTurn = Optional.empty();
        }
        if (existingTurn.isEmpty()) {
            session = latestSessionForNewTurn(session);
            requireSessionAcceptsTurn(session);
        }
        Instant now = Instant.now();
        SessionRuntimeStore.SessionRuntimeTurnData turn = repository.createOrReuseTurn(new SessionRuntimeStore.SessionRuntimeTurnData(
            nextId("session-turn"),
            session.id(),
            turnDedupKey,
            triggerType,
            TURN_STATUS_ALLOCATED_IDS,
            initialInputAllocations(inputs),
            List.of(),
            List.of(),
            List.of(),
            null,
            metadata == null ? Map.of() : metadata,
            now,
            now,
            null
        ));

        List<SessionMessage> turnMessages = repository.listMessagesForTurn(session.id(), turn.turnId());
        if (TURN_STATUS_WORKFLOW_ACCEPTED.equals(turn.status())) {
            return responseFromTurn(session.id(), turn, turnMessages, null);
        }

        TurnRecovery recovery = recoverTurn(session, turn, inputs, turnMessages);
        if (!recovery.messagesToAppend().isEmpty()) {
            List<SessionMessage> appended = repository.appendSessionMessages(
                session.id(),
                turn.turnId(),
                recovery.messagesToAppend()
            );
            turnMessages = new ArrayList<>(turnMessages);
            turnMessages.addAll(appended);
        }
        turnMessages = repository.listMessagesForTurn(session.id(), turn.turnId());
        List<SessionMessage> acceptedInputMessages = acceptedInputMessagesForTurn(turn, turnMessages);
        List<String> acceptedMessageIds = acceptedInputMessages.stream().map(SessionMessage::messageId).toList();
        List<String> turnMessageIds = turnMessages.stream().map(SessionMessage::messageId).toList();
        List<String> duplicateExternalMessageIds = recovery.duplicateExternalMessageIds();
        turn = repository.updateTurnState(
            session.id(),
            turn.turnId(),
            TURN_STATUS_MESSAGES_APPENDED,
            acceptedMessageIds,
            duplicateExternalMessageIds,
            turnMessageIds,
            turn.turnId(),
            null
        );
        if (acceptedMessageIds.isEmpty()) {
            return responseFromTurn(session.id(), turn, turnMessages, "duplicate messages ignored");
        }

        ensureWorkflowStarted(session);
        try {
            sessionWorkflowGateway.submitUserTurn(
                session.id(),
                turn.turnId(),
                new UserTurn(turn.turnId(), session.customerId(), turn.dedupKey(), acceptedInputMessages, turn.metadata())
            );
        } catch (RuntimeException error) {
            if (sessionWorkflowGateway.isWorkflowClosed(session.id())) {
                markEnded(session, Instant.now());
                throw new ConflictException("session has ended");
            }
            throw error;
        }
        turn = repository.updateTurnState(
            session.id(),
            turn.turnId(),
            TURN_STATUS_WORKFLOW_ACCEPTED,
            acceptedMessageIds,
            duplicateExternalMessageIds,
            turnMessageIds,
            turn.turnId(),
            null
        );
        return responseFromTurn(session.id(), turn, turnMessages, null);
    }

    private SessionRuntimeSessionDto latestSessionForNewTurn(SessionRuntimeSessionDto session) {
        Optional<SessionRuntimeSessionDto> latest = repository.findSession(session.id());
        if (latest == null) {
            throw new IllegalStateException("session reload returned null");
        }
        return latest.orElseThrow(() -> new IllegalStateException("session cannot be reloaded"));
    }

    private TurnRecovery recoverTurn(
        SessionRuntimeSessionDto session,
        SessionRuntimeStore.SessionRuntimeTurnData turn,
        List<TurnMessageInput> inputs,
        List<SessionMessage> existingTurnMessages
    ) {
        Map<Integer, TurnMessageInput> inputsByIndex = new LinkedHashMap<>();
        for (TurnMessageInput input : inputs) {
            inputsByIndex.put(input.requestIndex(), input);
        }
        Set<String> allocatedInputMessageIds = new HashSet<>();
        for (Map<String, Object> allocation : turn.inputAllocations()) {
            allocatedInputMessageIds.add(stringValue(allocation.get("messageId")));
        }
        Set<String> existingTurnMessageIds = new HashSet<>();
        Set<String> existingTurnExternalIds = new HashSet<>();
        for (SessionMessage message : existingTurnMessages) {
            existingTurnMessageIds.add(message.messageId());
            if (allocatedInputMessageIds.contains(message.messageId()) && hasText(message.externalMessageId())) {
                existingTurnExternalIds.add(message.externalMessageId());
            }
        }
        Set<String> alreadyPersistedExternalIds = new HashSet<>();
        for (SessionMessage message : repository.listMessages(session.id())) {
            if (hasText(message.externalMessageId()) && !turn.turnId().equals(message.turnId())) {
                alreadyPersistedExternalIds.add(message.externalMessageId());
            }
        }

        Set<String> acceptedExternalIds = new HashSet<>(existingTurnExternalIds);
        List<SessionRuntimeStore.SessionMessageAppendData> append = new ArrayList<>();
        List<String> duplicateExternalMessageIds = new ArrayList<>();
        for (Map<String, Object> allocation : turn.inputAllocations()) {
            int requestIndex = intValue(allocation.get("requestIndex"));
            String messageId = stringValue(allocation.get("messageId"));
            String externalMessageId = stringValue(allocation.get("externalMessageId"));
            if (existingTurnMessageIds.contains(messageId)) {
                continue;
            }
            if (hasText(externalMessageId)) {
                if (alreadyPersistedExternalIds.contains(externalMessageId) || acceptedExternalIds.contains(externalMessageId)) {
                    duplicateExternalMessageIds.add(externalMessageId);
                    continue;
                }
                acceptedExternalIds.add(externalMessageId);
            }
            TurnMessageInput input = inputsByIndex.get(requestIndex);
            if (input == null) {
                throw new IllegalArgumentException("turn replay is missing request index " + requestIndex);
            }
            append.add(toAppendData(messageId, externalMessageId, input));
        }
        return new TurnRecovery(append, duplicateExternalMessageIds);
    }

    private List<SessionMessage> acceptedInputMessagesForTurn(
        SessionRuntimeStore.SessionRuntimeTurnData turn,
        List<SessionMessage> turnMessages
    ) {
        Map<String, SessionMessage> messagesById = new LinkedHashMap<>();
        for (SessionMessage message : turnMessages) {
            messagesById.put(message.messageId(), message);
        }
        List<SessionMessage> acceptedInputMessages = new ArrayList<>();
        for (Map<String, Object> allocation : turn.inputAllocations()) {
            SessionMessage message = messagesById.get(stringValue(allocation.get("messageId")));
            if (message != null) {
                acceptedInputMessages.add(message);
            }
        }
        return acceptedInputMessages;
    }

    private SessionRuntimeStore.SessionMessageAppendData toAppendData(
        String messageId,
        String externalMessageId,
        TurnMessageInput input
    ) {
        Instant now = Instant.now();
        return new SessionRuntimeStore.SessionMessageAppendData(
            messageId,
            SessionMessageProducerType.EXTERNAL,
            externalMessageId,
            input.clientMessageId(),
            input.occurredAt(),
            input.role(),
            input.sender(),
            SessionMessageStatus.SENT,
            input.message().blocks(),
            input.metadata(),
            null,
            null,
            input.sourceEventId(),
            now,
            now
        );
    }

    private List<Map<String, Object>> initialInputAllocations(List<TurnMessageInput> inputs) {
        List<Map<String, Object>> allocations = new ArrayList<>(inputs.size());
        for (TurnMessageInput input : inputs) {
            Map<String, Object> allocation = new LinkedHashMap<>();
            allocation.put("requestIndex", input.requestIndex());
            allocation.put("messageId", nextId("session-message"));
            putIfPresent(allocation, "externalMessageId", input.externalMessageId());
            putIfPresent(allocation, "clientMessageId", input.clientMessageId());
            allocations.add(allocation);
        }
        return allocations;
    }

    private SendSessionTurnResponse responseFromTurn(
        String sessionId,
        SessionRuntimeStore.SessionRuntimeTurnData turn,
        List<SessionMessage> messages,
        String reason
    ) {
        Map<String, SessionMessage> messagesById = new LinkedHashMap<>();
        for (SessionMessage message : messages) {
            messagesById.put(message.messageId(), message);
        }
        List<AcceptedSessionMessageAllocation> allocations = new ArrayList<>();
        for (Map<String, Object> allocation : turn.inputAllocations()) {
            String messageId = stringValue(allocation.get("messageId"));
            SessionMessage message = messagesById.get(messageId);
            if (message == null) {
                continue;
            }
            allocations.add(new AcceptedSessionMessageAllocation(
                intValue(allocation.get("requestIndex")),
                stringValue(allocation.get("clientMessageId")),
                messageId,
                message.turnIndex()
            ));
        }
        List<String> acceptedMessageIds = allocations.stream()
            .map(AcceptedSessionMessageAllocation::messageId)
            .toList();
        return new SendSessionTurnResponse(
            sessionId,
            turn.turnId(),
            SessionMessageDeliveryStatus.ACCEPTED,
            acceptedMessageIds,
            allocations,
            turn.duplicateExternalMessageIds(),
            reason
        );
    }

    private void ensureWorkflowStarted(SessionRuntimeSessionDto session) {
        if (sessionWorkflowGateway.isWorkflowOpen(session.id())) {
            return;
        }
        AssistantDto assistant = catalogService.getAssistantRuntimeSnapshot(session.assistantId());
        AssistantReleaseDto release = resolveAssistantRelease(assistant);
        sessionWorkflowGateway.start(buildStartRequest(session, assistant, release));
    }

    private void requireSessionAcceptsTurn(SessionRuntimeSessionDto session) {
        if ("ENDED".equals(session.status())) {
            throw new ConflictException("session has ended");
        }
        if (session.draining()) {
            throw new ConflictException("workflow draining");
        }
        if (session.agentTurnActive()) {
            throw new ConflictException("session is busy");
        }
    }

    public SessionRuntimeSessionDto humanResume(String sessionId, HumanResumeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("human resume request is required");
        }
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        requirePlaybookRunInSession(sessionId, request.playbookRunId());
        String operatorId = currentUserResolver.resolveCurrentUser().id();
        String sourceEventId = firstNonBlank(
            request.resumeEventId(),
            stablePlatformEventId("human-resume", sessionId, request.playbookRunId(), operatorId, request.payload())
        );
        SessionRuntimeStore.SessionRuntimeTurnData turn = allocatePlatformTurn(
            sessionId,
            SessionTriggerType.HUMAN_RESUME,
            sourceEventId,
            sourceEventId,
            Map.of(
                "playbookRunId", request.playbookRunId(),
                "operatorId", operatorId,
                "payload", request.payload()
            )
        );
        sessionWorkflowGateway.humanResume(
            sessionId,
            new HumanResumeSignal(
                sessionId,
                turn.turnId(),
                turn.dedupKey(),
                sourceEventId,
                request.playbookRunId(),
                operatorId,
                request.payload()
            )
        );
        return awaitPersistedSession(sessionId, existing);
    }

    public SessionRuntimeSessionDto externalCallback(String sessionId, ExternalCallbackRequest request, String idempotencyKey) {
        String effectiveIdempotencyKey = externalCallbackIdempotencyKeyFactory.resolve(sessionId, request, idempotencyKey);
        return idempotencyService.execute(
            redisKeyspace.idempotency("session-external-callback", effectiveIdempotencyKey),
            SessionRuntimeSessionDto.class,
            () -> externalCallbackInternal(sessionId, request, effectiveIdempotencyKey)
        );
    }

    public SessionRuntimeSessionDto externalCallback(String sessionId, ExternalCallbackRequest request) {
        String effectiveIdempotencyKey = externalCallbackIdempotencyKeyFactory.resolve(sessionId, request, null);
        return externalCallbackInternal(sessionId, request, effectiveIdempotencyKey);
    }

    public SessionRuntimeSessionDto endHumanHandoff(String sessionId) {
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        String operatorId = currentUserResolver.resolveCurrentUser().id();
        sessionWorkflowGateway.endHumanHandoff(sessionId, new EndHumanHandoffSignal(sessionId, operatorId));
        return awaitPersistedSession(sessionId, existing);
    }

    public SessionRuntimeSessionDto humanOperatorReply(String sessionId, HumanOperatorReplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("human operator reply request is required");
        }
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        String operatorId = currentUserResolver.resolveCurrentUser().id();
        String operatorActionId = requireText(request.operatorActionId(), "operatorActionId");
        SessionMessageInput message = requireMessageInput(request.message(), "message");
        SessionRuntimeStore.SessionRuntimeTurnData turn = allocatePlatformTurn(
            sessionId,
            SessionTriggerType.HUMAN_OPERATOR_REPLY,
            operatorActionId,
            null,
            Map.of(
                "operatorActionId", operatorActionId,
                "operatorId", operatorId,
                "payload", request.payload()
            )
        );
        Instant now = Instant.now();
        repository.appendSessionMessages(
            sessionId,
            turn.turnId(),
            List.of(new SessionRuntimeStore.SessionMessageAppendData(
                stablePlatformMessageId(turn.turnId(), "human-operator-reply"),
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                now,
                SessionMessageRole.HUMAN_OPERATOR,
                new SessionMessageSender(SessionMessageSenderType.HUMAN_OPERATOR, operatorId, operatorId),
                SessionMessageStatus.SENT,
                message.blocks(),
                message.metadata(),
                existing.activePlaybookRunId(),
                existing.currentOwnerAgentId(),
                null,
                now,
                now
            ))
        );
        if (changeNoticePublisher != null) {
            changeNoticePublisher.publishSessionChanged(sessionId);
        }
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
            config.maxTokens(),
            config.privateDeployment(),
            effectiveEnableThinking(release, config),
            effectiveReasoningEffort(release, config)
        );
    }

    private LlmModelDescriptor resolvePrivacyModelDescriptor(
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

    private Boolean effectiveEnableThinking(AssistantReleaseDto release, LlmModelConfigDto config) {
        if (release.modelPolicy() != null && release.modelPolicy().enableThinking() != null) {
            return release.modelPolicy().enableThinking();
        }
        return config.enableThinking();
    }

    private String effectiveReasoningEffort(AssistantReleaseDto release, LlmModelConfigDto config) {
        if (release.modelPolicy() != null && release.modelPolicy().reasoningEffort() != null && !release.modelPolicy().reasoningEffort().isBlank()) {
            return release.modelPolicy().reasoningEffort();
        }
        return config.reasoningEffort();
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
            toToolConnectorDescriptor(tool.connector())
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

    private ToolConnectorDescriptor toToolConnectorDescriptor(ToolConnectorConfigDto connector) {
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

    private ToolConnectorRuntimeRetryPolicy toToolConnectorRuntimeRetryPolicy(String retryPolicyPreset) {
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

    private ToolConnectorAccountSnapshot toToolConnectorAccountSnapshot(com.lynxus.platform.catalog.CatalogDtos.ToolConnectorAccountSnapshotDto accountSnapshot) {
        if (accountSnapshot == null) {
            return null;
        }
        return new ToolConnectorAccountSnapshot(accountSnapshot.accountId(), accountSnapshot.runtimeSecretRef());
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
                    node.config(),
                    new com.lynxus.contracts.session.SessionContracts.PlaybookNodeLayout(
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

    private SessionRuntimeSessionDto resolveWebTurnSession(
        SendSessionTurnRequest request,
        String customerId,
        SessionMessageInput openingMessage
    ) {
        if (hasText(request.sessionId())) {
            SessionRuntimeSessionDto session = repository.findSession(requireText(request.sessionId(), "sessionId")).orElseThrow();
            requireWebSessionIdentity(session, customerId, requireText(request.assistantId(), "assistantId"));
            return requireExplicitSessionWorkflowAvailable(session);
        }
        String assistantId = requireText(request.assistantId(), "assistantId");
        return createOrReuseActiveSession(
            ENTRY_SCOPE_WEB,
            null,
            null,
            customerId,
            assistantId,
            openingMessage
        );
    }

    private SessionRuntimeSessionDto resolveImportSession(ImportSessionTarget target, SessionMessageInput openingMessage) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (target instanceof ExistingSessionImportTarget existingTarget) {
            SessionRuntimeSessionDto session = repository.findSession(requireText(existingTarget.sessionId(), "target.sessionId"))
                .orElseThrow();
            requireSessionCustomerAssistant(
                session,
                requireText(existingTarget.customerId(), "target.customerId"),
                requireText(existingTarget.assistantId(), "target.assistantId")
            );
            return requireExplicitSessionWorkflowAvailable(session);
        }
        if (target instanceof WebIdentityImportTarget webTarget) {
            return createOrReuseActiveSession(
                ENTRY_SCOPE_WEB,
                null,
                null,
                requireText(webTarget.customerId(), "target.customerId"),
                requireText(webTarget.assistantId(), "target.assistantId"),
                openingMessage
            );
        }
        if (target instanceof ChannelIdentityImportTarget channelTarget) {
            return resolveChannelTurnSession(
                null,
                requireText(channelTarget.channelProfileId(), "target.channelProfileId"),
                requireText(channelTarget.externalConversationId(), "target.externalConversationId"),
                requireText(channelTarget.customerId(), "target.customerId"),
                requireText(channelTarget.assistantId(), "target.assistantId"),
                openingMessage
            );
        }
        throw new IllegalArgumentException("unsupported import target");
    }

    private SessionRuntimeSessionDto resolveChannelTurnSession(
        String sessionId,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId,
        SessionMessageInput openingMessage
    ) {
        if (hasText(sessionId)) {
            SessionRuntimeSessionDto session = repository.findSession(requireText(sessionId, "sessionId")).orElseThrow();
            requireChannelSessionIdentity(session, channelProfileId, externalConversationId, customerId, assistantId);
            if (!channelBindingSessionEnded(session)) {
                return session;
            }
        }
        return createOrReuseActiveSession(
            ENTRY_SCOPE_CHANNEL,
            channelProfileId,
            externalConversationId,
            customerId,
            assistantId,
            openingMessage
        );
    }

    private boolean channelBindingSessionEnded(SessionRuntimeSessionDto session) {
        if ("ENDED".equals(session.status())) {
            return true;
        }
        if (sessionWorkflowGateway.isWorkflowClosed(session.id())) {
            markEnded(session, Instant.now());
            return true;
        }
        return false;
    }

    private SessionRuntimeSessionDto createOrReuseActiveSession(
        String entryScope,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId,
        SessionMessageInput openingMessage
    ) {
        AssistantDto assistant = catalogService.getAssistantRuntimeSnapshot(assistantId);
        AssistantReleaseDto release = resolveAssistantRelease(assistant);
        Instant now = Instant.now();
        Duration idleTimeout = release.sessionPolicy() == null
            ? Duration.ofMinutes(30)
            : parseDuration(release.sessionPolicy().idleTimeout(), Duration.ofMinutes(30));
        SessionRuntimeStore.SessionRuntimeSessionData initialSession = new SessionRuntimeStore.SessionRuntimeSessionData(
            nextId("session-v2"),
            assistant.scenarioId(),
            summarizeTitle(openingMessage),
            entryScope,
            channelProfileId,
            externalConversationId,
            customerId,
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
            1L,
            0L,
            now.plus(idleTimeout),
            now,
            now,
            null,
            0L,
            0L
        );
        Optional<SessionRuntimeSessionDto> active = findActiveSessionByIdentity(
            entryScope,
            channelProfileId,
            externalConversationId,
            customerId,
            assistant.id()
        );
        if (active.isPresent()) {
            SessionRuntimeSessionDto existing = active.orElseThrow();
            if (!sessionWorkflowGateway.isWorkflowClosed(existing.id())) {
                return existing;
            }
            markEnded(existing, Instant.now());
        }
        SessionRuntimeStore.SessionRuntimeSessionData persisted = repository.createOrReuseActiveSession(initialSession);
        SessionRuntimeSessionDto session = toSessionDto(persisted);
        if (!initialSession.id().equals(session.id()) && sessionWorkflowGateway.isWorkflowClosed(session.id())) {
            markEnded(session, Instant.now());
            persisted = repository.createOrReuseActiveSession(initialSession);
            session = toSessionDto(persisted);
        }
        return session;
    }

    private Optional<SessionRuntimeSessionDto> findActiveSessionByIdentity(
        String entryScope,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId
    ) {
        Optional<SessionRuntimeSessionDto> active;
        if (ENTRY_SCOPE_CHANNEL.equals(entryScope)) {
            active = repository.findActiveChannelSession(channelProfileId, externalConversationId, customerId, assistantId);
        } else {
            active = repository.findActiveSession(customerId, assistantId);
        }
        return active == null ? Optional.empty() : active;
    }

    private SessionRuntimeSessionDto requireExplicitSessionWorkflowAvailable(SessionRuntimeSessionDto session) {
        if (sessionWorkflowGateway.isWorkflowClosed(session.id())) {
            markEnded(session, Instant.now());
            throw new ConflictException("session has ended");
        }
        return session;
    }

    private List<TurnMessageInput> webTurnMessages(
        String customerId,
        List<WebSessionTurnMessageInput> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<TurnMessageInput> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index += 1) {
            WebSessionTurnMessageInput message = messages.get(index);
            if (message == null) {
                throw new IllegalArgumentException("messages[" + index + "] is required");
            }
            SessionMessageInput input = new SessionMessageInput(message.blocks(), message.metadata());
            if (isBlankMessageInput(input)) {
                throw new ConflictException("message content required");
            }
            result.add(new TurnMessageInput(
                index,
                null,
                message.clientMessageId(),
                message.occurredAt(),
                SessionMessageRole.USER,
                new SessionMessageSender(SessionMessageSenderType.CUSTOMER, customerId, customerId),
                input,
                input.metadata(),
                null
            ));
        }
        return result;
    }

    private List<TurnMessageInput> trustedImportMessages(
        List<TrustedImportSessionTurnMessage> messages,
        String sourceSystem,
        String importBatchId
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<TurnMessageInput> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index += 1) {
            TrustedImportSessionTurnMessage message = messages.get(index);
            if (message == null) {
                throw new IllegalArgumentException("messages[" + index + "] is required");
            }
            String canonicalExternalMessageId = canonicalImportExternalMessageId(message, sourceSystem);
            SessionMessageInput input = requireMessageInput(message.message(), "messages[" + index + "].message");
            Map<String, Object> metadata = mergedMetadata(input.metadata(), message.metadata());
            metadata.put("source", "trusted-import");
            metadata.put("sourceSystem", sourceSystem);
            metadata.put("importBatchId", importBatchId);
            putIfPresent(metadata, "importMessageId", message.importMessageId());
            result.add(new TurnMessageInput(
                index,
                canonicalExternalMessageId,
                null,
                message.occurredAt(),
                requireRole(message.role(), "messages[" + index + "].role"),
                requireSender(message.sender(), "messages[" + index + "].sender"),
                input,
                metadata,
                null
            ));
        }
        return result;
    }

    private List<TurnMessageInput> channelTurnMessages(
        ChannelInboundSessionTurnRequest request,
        String channelProfileId,
        String externalConversationId
    ) {
        List<ChannelInboundSessionTurnMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<TurnMessageInput> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index += 1) {
            ChannelInboundSessionTurnMessage message = messages.get(index);
            if (message == null) {
                throw new IllegalArgumentException("messages[" + index + "] is required");
            }
            String externalMessageId = requireText(message.externalMessageId(), "messages[" + index + "].externalMessageId");
            SessionMessageInput input = requireMessageInput(message.message(), "messages[" + index + "].message");
            Map<String, Object> metadata = mergedMetadata(input.metadata(), message.metadata());
            metadata.put("source", "channel-inbound");
            metadata.put("channelProfileId", channelProfileId);
            metadata.put("externalConversationId", externalConversationId);
            metadata.put("dedupKey", request.dedupKey());
            putIfPresent(metadata, "externalEventId", message.externalEventId());
            result.add(new TurnMessageInput(
                index,
                externalMessageId,
                null,
                message.occurredAt(),
                requireRole(message.role(), "messages[" + index + "].role"),
                requireSender(message.sender(), "messages[" + index + "].sender"),
                input,
                metadata,
                message.externalEventId()
            ));
        }
        return result;
    }

    private static SessionMessageInput requireMessageInput(SessionMessageInput input, String field) {
        if (input == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (isBlankMessageInput(input)) {
            throw new ConflictException("message content required");
        }
        return input;
    }

    private static SessionMessageRole requireRole(SessionMessageRole role, String field) {
        if (role == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return role;
    }

    private static SessionMessageSender requireSender(SessionMessageSender sender, String field) {
        if (sender == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (sender.senderType() == null) {
            throw new IllegalArgumentException(field + ".senderType is required");
        }
        return new SessionMessageSender(
            sender.senderType(),
            hasText(sender.senderId()) ? sender.senderId().trim() : null,
            requireText(sender.senderName(), field + ".senderName")
        );
    }

    private static String canonicalImportExternalMessageId(
        TrustedImportSessionTurnMessage message,
        String sourceSystem
    ) {
        if (hasText(message.externalMessageId())) {
            return message.externalMessageId().trim();
        }
        String importMessageId = requireText(message.importMessageId(), "importMessageId");
        return sourceSystem + ":" + importMessageId;
    }

    private void requireWebSessionIdentity(
        SessionRuntimeSessionDto session,
        String customerId,
        String assistantId
    ) {
        if (!ENTRY_SCOPE_WEB.equals(session.entryScope())
            || hasText(session.channelProfileId())
            || hasText(session.externalConversationId())) {
            throw new IllegalArgumentException("session entry scope does not match Web target");
        }
        requireSessionCustomerAssistant(session, customerId, assistantId);
    }

    private void requireChannelSessionIdentity(
        SessionRuntimeSessionDto session,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId
    ) {
        if (!ENTRY_SCOPE_CHANNEL.equals(session.entryScope())) {
            throw new IllegalArgumentException("session entry scope does not match Channel target");
        }
        if (!channelProfileId.equals(session.channelProfileId())
            || !externalConversationId.equals(session.externalConversationId())) {
            throw new IllegalArgumentException("channel identity does not match session");
        }
        requireSessionCustomerAssistant(session, customerId, assistantId);
    }

    private void requireSessionCustomerAssistant(
        SessionRuntimeSessionDto session,
        String customerId,
        String assistantId
    ) {
        if (!requireText(customerId, "customerId").equals(session.customerId())) {
            throw new IllegalArgumentException("customerId does not match session");
        }
        if (hasText(assistantId) && !assistantId.trim().equals(session.assistantId())) {
            throw new IllegalArgumentException("assistantId does not match session");
        }
    }

    private static Map<String, Object> mergedMetadata(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (first != null) {
            metadata.putAll(first);
        }
        if (second != null) {
            metadata.putAll(second);
        }
        return metadata;
    }

    private static SessionRuntimeSessionDto toSessionDto(SessionRuntimeStore.SessionRuntimeSessionData session) {
        return new SessionRuntimeSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.entryScope(),
            session.channelProfileId(),
            session.externalConversationId(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.status(),
            session.primaryAgentId(),
            session.currentOwnerAgentId(),
            session.activePlaybookRunId(),
            session.agentTurnActive(),
            session.sessionHumanHandoffActive(),
            session.pendingOwnerReevaluation(),
            session.draining(),
            session.sharedState(),
            session.sharedStateRevision(),
            session.idleDeadline(),
            session.createdAt(),
            session.updatedAt(),
            session.endedAt(),
            session.latestMessageSequence(),
            session.latestEventSequence()
        );
    }

    private SessionRuntimeSessionDto externalCallbackInternal(
        String sessionId,
        ExternalCallbackRequest request,
        String effectiveIdempotencyKey
    ) {
        if (request == null) {
            throw new IllegalArgumentException("external callback request is required");
        }
        SessionRuntimeSessionDto existing = repository.findSession(sessionId).orElseThrow();
        requirePlaybookRunInSession(sessionId, request.playbookRunId());
        String sourceEventId = stablePlatformEventId("external-callback", sessionId, effectiveIdempotencyKey);
        SessionRuntimeStore.SessionRuntimeTurnData turn = allocatePlatformTurn(
            sessionId,
            SessionTriggerType.EXTERNAL_CALLBACK,
            effectiveIdempotencyKey,
            sourceEventId,
            Map.of(
                "playbookRunId", request.playbookRunId(),
                "idempotencyKey", effectiveIdempotencyKey,
                "payload", request.payload()
            )
        );
        sessionWorkflowGateway.externalCallback(
            sessionId,
            new ExternalCallbackSignal(
                sessionId,
                turn.turnId(),
                turn.dedupKey(),
                sourceEventId,
                request.playbookRunId(),
                request.payload()
            )
        );
        return awaitPersistedSession(sessionId, existing);
    }

    private static Map<String, Integer> intMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), intValue(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static int intValue(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        if (rawValue == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(rawValue));
    }

    private static String stringValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = String.valueOf(rawValue);
        return value.isBlank() ? null : value;
    }

    private SessionRuntimeSessionDto markEndedIfWorkflowClosed(SessionRuntimeSessionDto existing) {
        if ("ENDED".equals(existing.status())) {
            return existing;
        }
        if (sessionWorkflowGateway.isWorkflowClosed(existing.id())) {
            return markEnded(existing, Instant.now());
        }
        return existing;
    }

    private SessionRuntimeSessionDto markEnded(SessionRuntimeSessionDto existing, Instant now) {
        SessionRuntimeSessionDto ended = new SessionRuntimeSessionDto(
            existing.id(),
            existing.scenarioId(),
            existing.title(),
            existing.entryScope(),
            existing.channelProfileId(),
            existing.externalConversationId(),
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
            existing.sharedStateRevision(),
            null,
            existing.createdAt(),
            now,
            existing.endedAt() == null ? now : existing.endedAt(),
            existing.latestMessageSequence(),
            existing.latestEventSequence()
        );
        repository.saveSession(ended);
        if (changeNoticePublisher != null) {
            changeNoticePublisher.publishSessionChanged(existing.id());
        }
        return ended;
    }

    private SessionRuntimeSessionDto awaitPersistedSession(String sessionId, SessionRuntimeSessionDto fallback) {
        SessionRuntimeSessionDto latestSeen = fallback;
        for (int attempt = 0; attempt < 20; attempt += 1) {
            Optional<SessionRuntimeSessionDto> persisted = repository.findSession(sessionId);
            if (persisted.isPresent()) {
                SessionRuntimeSessionDto current = persisted.orElseThrow();
                latestSeen = current;
                if (hasObservableSessionChange(current, fallback)) {
                    return markEndedIfWorkflowClosed(current);
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return markEndedIfWorkflowClosed(latestSeen);
    }

    private static boolean hasObservableSessionChange(
        SessionRuntimeSessionDto current,
        SessionRuntimeSessionDto baseline
    ) {
        return !current.equals(baseline);
    }

    private void requirePlaybookRunInSession(String sessionId, String playbookRunId) {
        boolean owned = repository.listPlaybookRuns(sessionId).stream()
            .anyMatch(run -> run.runId().equals(playbookRunId));
        if (!owned) {
            throw new ConflictException("playbook run does not belong to session");
        }
    }

    private SessionRuntimeStore.SessionRuntimeTurnData allocatePlatformTurn(
        String sessionId,
        SessionTriggerType triggerType,
        String dedupKey,
        String sourceEventId,
        Map<String, Object> metadata
    ) {
        return repository.allocatePlatformTurn(
            sessionId,
            triggerType.name(),
            dedupKey,
            sourceEventId,
            metadata == null ? Map.of() : metadata
        );
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireMatchingIdempotencyKey(String idempotencyKey, String requestDedupKey, String requestField) {
        String headerValue = requireText(idempotencyKey, "Idempotency-Key");
        String bodyValue = requireText(requestDedupKey, requestField);
        if (!headerValue.equals(bodyValue)) {
            throw new IllegalArgumentException("Idempotency-Key must equal " + requestField);
        }
        return bodyValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
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

    private static String summarizeTitle(SessionMessageInput openingMessage) {
        String text = firstRenderableText(openingMessage);
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        return text.length() <= 24 ? text : text.substring(0, 24);
    }

    private static boolean isBlankMessageInput(SessionMessageInput input) {
        return !hasRenderableMessageContent(input);
    }

    private static String firstRenderableText(SessionMessageInput input) {
        if (input == null || input.blocks() == null || input.blocks().isEmpty()) {
            return null;
        }
        for (Object block : input.blocks()) {
            if (!(block instanceof Map<?, ?> entry)) {
                continue;
            }
            Object type = entry.get("type");
            Object value = "TEXT".equals(type) ? entry.get("text") : "RICH_TEXT".equals(type) ? entry.get("content") : null;
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private static boolean hasRenderableMessageContent(SessionMessageInput input) {
        if (input == null || input.blocks() == null || input.blocks().isEmpty()) {
            return false;
        }
        for (Object block : input.blocks()) {
            if (!(block instanceof Map<?, ?> entry)) {
                continue;
            }
            Object type = entry.get("type");
            Object value = "TEXT".equals(type) ? entry.get("text") : "RICH_TEXT".equals(type) ? entry.get("content") : null;
            if (value instanceof String text && !text.isBlank()) {
                return true;
            }
            if ("IMAGE".equals(type) || "CARD".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String stablePlatformEventId(String source, Object... parts) {
        return "session-event-" + sha256Hex(source + ":" + List.of(parts)).substring(0, 16);
    }

    private static String stablePlatformMessageId(String turnId, String purpose) {
        return "session-message-" + sha256Hex(turnId + ":" + purpose).substring(0, 16);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte current : encoded) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("failed to hash platform turn key", error);
        }
    }

    private record TurnMessageInput(
        int requestIndex,
        String externalMessageId,
        String clientMessageId,
        Instant occurredAt,
        SessionMessageRole role,
        SessionMessageSender sender,
        SessionMessageInput message,
        Map<String, Object> metadata,
        String sourceEventId
    ) {
        private TurnMessageInput {
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    private record TurnRecovery(
        List<SessionRuntimeStore.SessionMessageAppendData> messagesToAppend,
        List<String> duplicateExternalMessageIds
    ) {
        private TurnRecovery {
            messagesToAppend = messagesToAppend == null ? List.of() : List.copyOf(messagesToAppend);
            duplicateExternalMessageIds = duplicateExternalMessageIds == null ? List.of() : List.copyOf(duplicateExternalMessageIds);
        }
    }
}
