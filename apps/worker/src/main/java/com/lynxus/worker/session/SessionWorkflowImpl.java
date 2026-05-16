package com.lynxus.worker.session;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionContracts.ActivePlaybookSummary;
import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentDecision;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.session.SessionContracts.AgentRuntimeContextEntry;
import com.lynxus.contracts.session.SessionContracts.AgentRuntimeContextEntryType;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnResult;
import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.EndHumanHandoffSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookProgressType;
import com.lynxus.contracts.session.SessionContracts.PlaybookProgressUpdate;
import com.lynxus.contracts.session.SessionContracts.PlaybookResumeSignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookResumeSource;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.PlaybookStartRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookWaitingType;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionTrigger;
import com.lynxus.contracts.session.SessionContracts.SessionTriggerType;
import com.lynxus.contracts.session.SessionContracts.SecurityAssessment;
import com.lynxus.contracts.session.SessionContracts.UserTurn;
import com.lynxus.contracts.session.SessionContracts.UserTurnAcceptedResult;
import com.lynxus.contracts.session.SessionWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

public class SessionWorkflowImpl implements SessionWorkflow {
    private static final String DECISION_REJECTED_REPLY = "当前无法完成该操作，请稍后再试";
    private static final String TURN_FAILED_REPLY = "当前处理遇到问题，请稍后再试";
    private static final String SECURITY_BLOCKED_REPLY = "为了保护系统安全，我不能处理这类请求。";
    private static final String SYSTEM_SENDER_NAME = "System";
    private static final int SHARED_STATE_PATCH_SNAPSHOT_ENTRY_LIMIT = 20;
    private static final int SHARED_STATE_PATCH_SNAPSHOT_CHAR_LIMIT = 12_000;

    private record DecisionValidation(
        AgentDecision decision,
        String rejectReason,
        boolean sessionHandoffIdempotent
    ) {
        private static DecisionValidation accepted(AgentDecision decision) {
            return new DecisionValidation(decision, null, false);
        }

        private static DecisionValidation idempotentSessionHandoff(AgentDecision decision) {
            return new DecisionValidation(decision, null, true);
        }

        private static DecisionValidation rejected(String rejectReason) {
            return new DecisionValidation(null, rejectReason, false);
        }

        private boolean accepted() {
            return decision != null;
        }
    }

    private final AgentTurnActivities activities;
    private final SessionPersistenceActivities persistenceActivities;
    private final JsonSchemaValidator jsonSchemaValidator;
    private SessionStartRequest startRequest;
    private SessionSnapshot snapshot;
    private final Map<String, AgentConfig> agentsById = new LinkedHashMap<>();
    private final Map<String, PlaybookConfig> playbooksById = new LinkedHashMap<>();
    private final Map<String, PlaybookRun> playbookRunsById = new LinkedHashMap<>();
    private final Map<String, UserTurnAcceptedResult> processedTurnsById = new LinkedHashMap<>();
    private final List<AgentRuntimeContextEntry> pendingContextEntries = new ArrayList<>();
    private final Set<String> emittedContextEntryKeys = new LinkedHashSet<>();
    private final Set<String> bootstrappedProviderContexts = new LinkedHashSet<>();
    private final List<SessionMessage> messages = new ArrayList<>();
    private final List<SessionEvent> events = new ArrayList<>();
    private Promise<PlaybookRun> activePlaybookCompletion;
    private PlaybookWorkflow activePlaybookWorkflow;
    private String activePlaybookWorkflowId;
    private SessionTrigger pendingOwnerReevaluationTrigger;
    private Instant workflowStartedAt;
    private Instant sessionCreatedAt;
    private Instant sharedStateUpdatedAt;
    private String activeTurnId;
    private long ownershipEpoch = 1;
    private long sharedStateRevision = 0;
    private boolean ended;

    public SessionWorkflowImpl() {
        this(Duration.ofMinutes(2));
    }

    public SessionWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.activities = Workflow.newActivityStub(
            AgentTurnActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofSeconds(10))
                        .setBackoffCoefficient(2.0)
                        .build()
                )
                .build()
        );
        this.persistenceActivities = Workflow.newActivityStub(
            SessionPersistenceActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build()
        );
        this.jsonSchemaValidator = new JsonSchemaValidator(new ObjectMapper());
    }

    @Override
    public SessionSnapshot run(SessionStartRequest request) {
        this.startRequest = request;
        this.agentsById.clear();
        this.playbooksById.clear();
        this.playbookRunsById.clear();
        this.processedTurnsById.clear();
        this.pendingContextEntries.clear();
        this.emittedContextEntryKeys.clear();
        this.bootstrappedProviderContexts.clear();
        this.messages.clear();
        this.events.clear();
        this.activePlaybookCompletion = null;
        this.activePlaybookWorkflow = null;
        this.activePlaybookWorkflowId = null;
        this.pendingOwnerReevaluationTrigger = null;
        this.workflowStartedAt = now();
        this.sessionCreatedAt = workflowStartedAt;
        this.sharedStateUpdatedAt = workflowStartedAt;
        this.activeTurnId = null;
        this.ownershipEpoch = 1;
        this.sharedStateRevision = 0;
        this.ended = false;
        request.agents().forEach(agent -> agentsById.put(agent.agentId(), agent));
        request.playbooks().forEach(playbook -> playbooksById.put(playbook.playbookId(), playbook));
        setSnapshot(new SessionSnapshot(
            request.sessionId(),
            request.assistant().assistantId(),
            request.assistant().assistantReleaseVersion(),
            request.assistant().primaryAgentId(),
            request.assistant().primaryAgentId(),
            0,
            request.initialSharedState(),
            null,
            false,
            false,
            false,
            false,
            nextIdleDeadline()
        ));
        while (!ended) {
            applyGuardrails();
            if (shouldEndDrainingSession()) {
                ended = true;
                continue;
            }
            if (shouldEndIdleSession()) {
                ended = true;
                continue;
            }
            Workflow.await(nextLifecycleCheckDelay(), () -> ended || (activePlaybookCompletion != null && activePlaybookCompletion.isCompleted()));
            if (activePlaybookCompletion != null && activePlaybookCompletion.isCompleted()) {
                completeActivePlaybook(activePlaybookCompletion.get());
            }
        }
        persistSession(statusForSnapshot(true), now());
        return snapshot;
    }

    @Override
    public UserTurnAcceptedResult submitUserTurn(UserTurn turn) {
        UserTurnAcceptedResult processed = processedTurnsById.get(turn.turnId());
        if (processed != null) {
            return new UserTurnAcceptedResult(processed.sessionId(), processed.turnId(), true, processed.reason());
        }
        appendPersistedTurnMessages(turn.messages());
        if (snapshot.sessionHumanHandoffActive()) {
            UserTurnAcceptedResult result = new UserTurnAcceptedResult(
                snapshot.sessionId(),
                turn.turnId(),
                false,
                "session human handoff active"
            );
            processedTurnsById.put(turn.turnId(), result);
            return result;
        }
        List<String> messageIds = turn.messages().stream().map(SessionMessage::messageId).toList();
        executeTurn(
            new SessionTrigger(
                SessionTriggerType.USER_MESSAGE,
                turn.turnId(),
                null,
                Map.of(
                    "customerId", turn.customerId(),
                    "turnDedupKey", turn.turnDedupKey(),
                    "messageIds", messageIds
                )
            ),
            turn.messages()
        );
        UserTurnAcceptedResult result = new UserTurnAcceptedResult(snapshot.sessionId(), turn.turnId(), false, null);
        processedTurnsById.put(turn.turnId(), result);
        return result;
    }

    @Override
    public void validateSubmitUserTurn(UserTurn turn) {
        requireValidUserTurn(turn);
        if (processedTurnsById.containsKey(turn.turnId())) {
            return;
        }
        if (snapshot == null) {
            throw new IllegalStateException("workflow is not initialized");
        }
        if (snapshot.draining() || ended) {
            throw new IllegalStateException("workflow draining");
        }
        if (snapshot.agentTurnActive()) {
            throw new IllegalStateException("agent turn active");
        }
    }

    @Override
    public void humanResume(HumanResumeSignal signal) {
        if (signal == null) {
            return;
        }
        if (!hasText(signal.turnId()) || !hasText(signal.sourceEventId())) {
            return;
        }
        PlaybookRun run = playbookRunsById.get(signal.playbookRunId());
        if (!canAcceptResumeSignal(signal.sessionId(), run, PlaybookWaitingType.HUMAN_TASK)) {
            return;
        }
        appendEvent(
            signal.sourceEventId(),
            SessionEventType.HUMAN_RESUME_RECEIVED,
            SessionActorType.HUMAN_OPERATOR,
            signal.operatorId(),
            Map.of("playbookRunId", signal.playbookRunId(), "payload", signal.payload()),
            signal.playbookRunId(),
            snapshot.currentOwnerAgentId()
        );
        activePlaybookWorkflow.resume(new PlaybookResumeSignal(signal.playbookRunId(), PlaybookResumeSource.HUMAN, signal.operatorId(), signal.payload()));
    }

    @Override
    public void externalCallback(ExternalCallbackSignal signal) {
        if (signal == null) {
            return;
        }
        if (!hasText(signal.turnId()) || !hasText(signal.sourceEventId())) {
            return;
        }
        PlaybookRun run = playbookRunsById.get(signal.playbookRunId());
        if (!canAcceptResumeSignal(signal.sessionId(), run, PlaybookWaitingType.EXTERNAL_INTERACTION)) {
            return;
        }
        appendEvent(
            signal.sourceEventId(),
            SessionEventType.EXTERNAL_CALLBACK_RECEIVED,
            SessionActorType.EXTERNAL_SYSTEM,
            null,
            Map.of("playbookRunId", signal.playbookRunId(), "payload", signal.payload()),
            signal.playbookRunId(),
            snapshot.currentOwnerAgentId()
        );
        activePlaybookWorkflow.resume(new PlaybookResumeSignal(signal.playbookRunId(), PlaybookResumeSource.EXTERNAL_SYSTEM, null, signal.payload()));
    }

    @Override
    public void endHumanHandoff(EndHumanHandoffSignal signal) {
        if (!snapshot.sessionHumanHandoffActive() || signal == null) {
            return;
        }
        appendEvent(
            SessionEventType.SESSION_HUMAN_HANDOFF_ENDED,
            SessionActorType.HUMAN_OPERATOR,
            signal.operatorId(),
            Map.of(),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId()
        );
        setSnapshot(copySnapshot(
            snapshot.sharedState(),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId(),
            0,
            snapshot.agentTurnActive(),
            false,
            false,
            snapshot.draining()
        ));
        endWorkflowIfDrainingSafePointReached();
    }

    @Override
    public void humanOperatorReply(HumanOperatorReplySignal signal) {
        if (!snapshot.sessionHumanHandoffActive() || signal == null || !hasMessageContent(signal.message())) {
            return;
        }
        String platformTurnId = signal.turnId();
        if (!hasText(platformTurnId)) {
            return;
        }
        String previousActiveTurnId = activeTurnId;
        activeTurnId = platformTurnId;
        try {
            appendPlatformMessage(
                SessionMessageRole.HUMAN_OPERATOR,
                SessionMessageSenderType.HUMAN_OPERATOR,
                signal.operatorId(),
                signal.operatorId(),
                signal.message(),
                snapshot.activePlaybookRunId(),
                snapshot.currentOwnerAgentId(),
                null
            );
        } finally {
            activeTurnId = previousActiveTurnId;
        }
    }

    @Override
    public void syncPlaybookProgress(PlaybookProgressUpdate update) {
        if (update == null || update.run() == null) {
            return;
        }
        PlaybookRun previousRun = playbookRunsById.get(update.run().runId());
        String sourceEventId = null;
        if (!update.run().runId().equals(snapshot.activePlaybookRunId())) {
            persistPlaybookRun(update.run(), previousRun, null, null);
            return;
        }
        if (update.progressType() == PlaybookProgressType.WAITING && shouldAppendWaitingEvent(previousRun, update.run())) {
            sourceEventId = appendEvent(
                SessionEventType.PLAYBOOK_WAITING,
                SessionActorType.SYSTEM,
                null,
                Map.of(
                    "runId", update.run().runId(),
                    "nodeKey", update.nodeKey(),
                    "waitingType", update.waitingType() == null ? null : update.waitingType().name(),
                    "waitingReason", update.run().waitingReason()
                ),
                update.run().runId(),
                update.run().ownerAgentId()
            );
        }
        if (update.progressType() == PlaybookProgressType.RESUMED && shouldAppendResumedEvent(previousRun, update.run())) {
            sourceEventId = appendEvent(
                SessionEventType.PLAYBOOK_RESUMED,
                update.resumeSource() == PlaybookResumeSource.HUMAN ? SessionActorType.HUMAN_OPERATOR : SessionActorType.EXTERNAL_SYSTEM,
                update.resumeSource() == PlaybookResumeSource.HUMAN ? update.operatorId() : null,
                Map.of(
                    "runId", update.run().runId(),
                    "nodeKey", update.nodeKey(),
                    "resumeSource", update.resumeSource() == null ? null : update.resumeSource().name(),
                    "payload", update.payload()
                ),
                update.run().runId(),
                update.run().ownerAgentId()
            );
        }
        persistPlaybookRun(update.run(), previousRun, sourceEventId, null);
        if (sourceEventId != null) {
            addActivePlaybookSummaryContextEntry(update.run(), sourceEventId);
        }
        setSnapshot(copySnapshot(
            snapshot.sharedState(),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId(),
            snapshot.ownerSwitchCountInTurn(),
            snapshot.agentTurnActive(),
            snapshot.sessionHumanHandoffActive(),
            pendingOwnerReevaluationTrigger != null,
            snapshot.draining()
        ));
    }

    @Override
    public SessionSnapshot currentSnapshot() {
        return snapshot;
    }

    private void executeTurn(SessionTrigger trigger, List<SessionMessage> turnMessages) {
        if (processedTurnsById.containsKey(trigger.turnId())) {
            return;
        }
        String previousActiveTurnId = activeTurnId;
        activeTurnId = trigger.turnId();
        int switchCount = 0;
        int turnExecutionSequence = 1;
        String currentOwnerAgentId = snapshot.currentOwnerAgentId();
        String activePlaybookRunId = snapshot.activePlaybookRunId();
        boolean sessionHumanHandoffActive = snapshot.sessionHumanHandoffActive();
        Map<String, Object> sharedState = snapshot.sharedState();
        try {
            setSnapshot(copySnapshot(
                sharedState,
                activePlaybookRunId,
                currentOwnerAgentId,
                switchCount,
                true,
                sessionHumanHandoffActive,
                false,
                snapshot.draining()
            ));

            while (true) {
                AgentConfig owner = agentsById.get(currentOwnerAgentId);
                if (owner == null) {
                    emitDecisionRejected("missing current owner agent", null, currentOwnerAgentId, activePlaybookRunId);
                    break;
                }
                AgentTurnExecutionOutcome outcome;
                String turnExecutionId = trigger.turnId() + ":exec-" + turnExecutionSequence;
                String replyMessageId = nextMessageId();
                boolean transcriptBootstrap = shouldBootstrapTranscript(owner);
                List<SessionMessage> requestMessages = transcriptBootstrap
                    ? bootstrapMessages(turnMessages)
                    : orderedMessages(turnMessages);
                List<AgentRuntimeContextEntry> contextEntries = contextEntriesForRequest(
                    transcriptBootstrap,
                    sharedState,
                    activePlaybookRunId
                );
                try {
                    outcome = activities.executeTurn(new AgentTurnRequest(
                        snapshot.sessionId(),
                        trigger.turnId(),
                        turnExecutionId,
                        replyMessageId,
                        ownershipEpoch,
                        snapshot.assistantId(),
                        snapshot.assistantReleaseVersion(),
                        owner,
                        startRequest.agents(),
                        startRequest.playbooks(),
                        activePlaybookSummary(activePlaybookRunId),
                        sharedState,
                        owner.effectivePrivacyModelBinding(),
                        owner.effectivePrivacyMappingEnabled(),
                        trigger,
                        requestMessages,
                        contextEntries,
                        transcriptBootstrap
                    ));
                } catch (RuntimeException error) {
                    emitSystemEventBackedReply(
                        SessionEventType.AGENT_TURN_FAILED,
                        currentOwnerAgentId,
                        Map.of("reason", error.getMessage() == null ? "agent turn failed" : error.getMessage(), "triggerType", trigger.triggerType().name()),
                        activePlaybookRunId,
                        currentOwnerAgentId,
                        textMessageInput(TURN_FAILED_REPLY),
                        replyMessageId
                    );
                    break;
                }

                if (outcome != null && outcome.llmUsage() != null && !outcome.llmUsage().isEmpty()) {
                    persistLlmUsage(trigger, turnExecutionId, currentOwnerAgentId, activePlaybookRunId, outcome.llmUsage());
                }
                if (outcome == null || !outcome.success() || outcome.result() == null) {
                    String failureReason = outcome == null || outcome.failureReason() == null || outcome.failureReason().isBlank()
                        ? "agent turn failed"
                        : outcome.failureReason();
                    emitSystemEventBackedReply(
                        SessionEventType.AGENT_TURN_FAILED,
                        currentOwnerAgentId,
                        Map.of("reason", failureReason, "triggerType", trigger.triggerType().name()),
                        activePlaybookRunId,
                        currentOwnerAgentId,
                        textMessageInput(TURN_FAILED_REPLY),
                        replyMessageId
                    );
                    break;
                }
                markContextEntriesEmitted(contextEntries);
                if (transcriptBootstrap) {
                    bootstrappedProviderContexts.add(providerContextKey(owner));
                }
                AgentTurnResult result = outcome.result();
                SecurityAssessment securityAssessment = result == null ? null : result.securityAssessment();
                if (securityAssessment != null && isSecurityBlocked(securityAssessment)) {
                    AgentDecision decision = result == null ? null : result.decision();
                    SessionMessageInput assistantReply = decision == null ? null : decision.replyMessage();
                    boolean assistantReplyEmitted = hasMessageContent(assistantReply);
                    if (assistantReplyEmitted) {
                        emitOwnerReply(replyMessageId, assistantReply, SessionActorType.AGENT, currentOwnerAgentId, activePlaybookRunId, currentOwnerAgentId, null);
                    }
                    emitSecurityBlocked(
                        securityAssessment,
                        trigger,
                        currentOwnerAgentId,
                        activePlaybookRunId,
                        assistantReplyEmitted ? null : replyMessageId
                    );
                    break;
                }
                Map<String, Object> previousSharedState = sharedState;
                sharedState = result == null ? sharedState : result.sharedState();
                if (!Objects.equals(previousSharedState, sharedState)) {
                    sharedStateRevision += 1;
                    sharedStateUpdatedAt = now();
                    addSharedStatePatchContextEntry(previousSharedState, sharedState, sharedStateUpdatedAt);
                }
                if (result != null && result.mappingTelemetry() != null) {
                    appendPrivacyMappingAuditEvents(currentOwnerAgentId, result.mappingTelemetry());
                }
                setSnapshot(copySnapshot(
                    sharedState,
                    activePlaybookRunId,
                    currentOwnerAgentId,
                    switchCount,
                    true,
                    sessionHumanHandoffActive,
                    pendingOwnerReevaluationTrigger != null,
                    snapshot.draining()
                ));
                DecisionValidation validation = validateDecision(
                    owner,
                    currentOwnerAgentId,
                    activePlaybookRunId,
                    switchCount,
                    sessionHumanHandoffActive,
                    result == null ? null : result.decision()
                );
                if (!validation.accepted()) {
                    emitDecisionRejected(
                        validation.rejectReason(),
                        result == null ? null : result.decision(),
                        currentOwnerAgentId,
                        activePlaybookRunId,
                        replyMessageId
                    );
                    break;
                }
                AgentDecision decision = validation.decision();

                boolean assistantReplyEmitted = hasMessageContent(decision.replyMessage());
                if (assistantReplyEmitted) {
                    emitOwnerReply(replyMessageId, decision.replyMessage(), SessionActorType.AGENT, currentOwnerAgentId, activePlaybookRunId, currentOwnerAgentId, null);
                }
                if (decision.action() == AgentDecisionAction.REPLY || decision.action() == AgentDecisionAction.NO_OP) {
                    break;
                }

                if (decision.action() == AgentDecisionAction.SECURITY_BLOCK) {
                    emitSecurityBlocked(
                        securityAssessmentOrDefault(result == null ? null : result.securityAssessment()),
                        trigger,
                        currentOwnerAgentId,
                        activePlaybookRunId,
                        assistantReplyEmitted ? null : replyMessageId
                    );
                    break;
                }

                if (decision.action() == AgentDecisionAction.SWITCH_OWNER) {
                    String previousOwnerAgentId = currentOwnerAgentId;
                    currentOwnerAgentId = decision.targetAgentId();
                    switchCount += 1;
                    turnExecutionSequence += 1;
                    ownershipEpoch += 1;
                    appendEvent(
                        SessionEventType.OWNER_SWITCH,
                        SessionActorType.AGENT,
                        previousOwnerAgentId,
                        Map.of("fromOwnerAgentId", previousOwnerAgentId, "toOwnerAgentId", currentOwnerAgentId),
                        activePlaybookRunId,
                        currentOwnerAgentId
                    );
                    setSnapshot(copySnapshot(
                        sharedState,
                        activePlaybookRunId,
                        currentOwnerAgentId,
                        switchCount,
                        true,
                        sessionHumanHandoffActive,
                        false,
                        snapshot.draining()
                    ));
                    continue;
                }

                if (decision.action() == AgentDecisionAction.RUN_PLAYBOOK) {
                    activePlaybookRunId = startPlaybook(decision.playbookId(), decision.playbookInput(), currentOwnerAgentId);
                    break;
                }

                if (decision.action() == AgentDecisionAction.SESSION_HUMAN_HANDOFF) {
                    if (validation.sessionHandoffIdempotent()) {
                        break;
                    }
                    sessionHumanHandoffActive = true;
                    pendingOwnerReevaluationTrigger = null;
                    appendEvent(
                        SessionEventType.SESSION_HUMAN_HANDOFF_STARTED,
                        SessionActorType.AGENT,
                        currentOwnerAgentId,
                        humanHandoffStartedPayload(decision),
                        activePlaybookRunId,
                        currentOwnerAgentId
                    );
                    break;
                }
            }

            setSnapshot(copySnapshot(
                sharedState,
                activePlaybookRunId,
                currentOwnerAgentId,
                switchCount,
                false,
                sessionHumanHandoffActive,
                pendingOwnerReevaluationTrigger != null,
                snapshot.draining()
            ));
            processedTurnsById.putIfAbsent(
                trigger.turnId(),
                new UserTurnAcceptedResult(snapshot.sessionId(), trigger.turnId(), false, null)
            );
            finishTurn();
        } finally {
            activeTurnId = previousActiveTurnId;
        }
    }

    private void finishTurn() {
        setSnapshot(copySnapshot(
            snapshot.sharedState(),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId(),
            0,
            false,
            snapshot.sessionHumanHandoffActive(),
            pendingOwnerReevaluationTrigger != null,
            snapshot.draining()
        ));
        if (snapshot.draining()) {
            if (pendingOwnerReevaluationTrigger != null) {
                pendingOwnerReevaluationTrigger = null;
                setSnapshot(copySnapshot(
                    snapshot.sharedState(),
                    snapshot.activePlaybookRunId(),
                    snapshot.currentOwnerAgentId(),
                    snapshot.ownerSwitchCountInTurn(),
                    false,
                    snapshot.sessionHumanHandoffActive(),
                    false,
                    snapshot.draining()
                ));
            }
            endWorkflowIfDrainingSafePointReached();
            return;
        }
        if (snapshot.sessionHumanHandoffActive() || pendingOwnerReevaluationTrigger == null) {
            return;
        }
        SessionTrigger trigger = pendingOwnerReevaluationTrigger;
        pendingOwnerReevaluationTrigger = null;
        executeTurn(trigger, List.of());
    }

    private String startPlaybook(String playbookId, Map<String, Object> playbookInput, String ownerAgentId) {
        PlaybookConfig playbook = playbooksById.get(playbookId);
        String runId = "playbook-run-" + playbookId + "-" + Workflow.randomUUID();
        String startedEventId = appendEvent(
            SessionEventType.PLAYBOOK_STARTED,
            SessionActorType.AGENT,
            ownerAgentId,
            Map.of("runId", runId, "playbookId", playbookId, "input", playbookInput),
            runId,
            ownerAgentId
        );
        PlaybookRun run = new PlaybookRun(
            runId,
            snapshot.sessionId(),
            startedEventId,
            playbookId,
            ownerAgentId,
            PlaybookRunStatus.RUNNING,
            playbookInput,
            Map.of(),
            null,
            now(),
            now(),
            null
        );
        playbookRunsById.put(runId, run);
        addActivePlaybookSummaryContextEntry(run, startedEventId);
        persistPlaybookRun(run, null, startedEventId, ownerAgentId);
        activePlaybookWorkflowId = snapshot.sessionId() + ":" + runId;
        activePlaybookWorkflow = Workflow.newChildWorkflowStub(
            PlaybookWorkflow.class,
            ChildWorkflowOptions.newBuilder().setWorkflowId(activePlaybookWorkflowId).build()
        );
        activePlaybookCompletion = Async.function(
            activePlaybookWorkflow::run,
            new PlaybookStartRequest(
                snapshot.sessionId(),
                runId,
                startedEventId,
                ownerAgentId,
                agentsById.get(ownerAgentId),
                playbook,
                playbookInput
            )
        );
        return runId;
    }

    private void completeActivePlaybook(PlaybookRun completedRun) {
        String previousRunId = snapshot.activePlaybookRunId();
        if (completedRun != null) {
            PlaybookRun previousRun = playbookRunsById.get(completedRun.runId());
            Map<String, Object> playbookCompletedPayload = new LinkedHashMap<>();
            playbookCompletedPayload.put("status", completedRun.status().name());
            playbookCompletedPayload.put("result", completedRun.result());
            if (completedRun.failureReason() != null) {
                playbookCompletedPayload.put("failureReason", completedRun.failureReason());
            }
            String sourceEventId = appendEvent(
                SessionEventType.PLAYBOOK_COMPLETED,
                SessionActorType.SYSTEM,
                null,
                playbookCompletedPayload,
                completedRun.runId(),
                completedRun.ownerAgentId()
            );
            persistPlaybookRun(completedRun, previousRun, sourceEventId, null);
            addActivePlaybookSummaryContextEntry(completedRun, sourceEventId);
            if (!snapshot.sessionHumanHandoffActive() && !snapshot.draining()) {
                Map<String, Object> reevaluationPayload = new LinkedHashMap<>();
                reevaluationPayload.put("playbookRunId", completedRun.runId());
                reevaluationPayload.put("status", completedRun.status().name());
                reevaluationPayload.put("result", completedRun.result());
                if (completedRun.failureReason() != null) {
                    reevaluationPayload.put("failureReason", completedRun.failureReason());
                }
                SessionPersistenceActivities.PlatformTurnAllocation platformTurn = allocatePlatformTurn(
                    SessionTriggerType.PLAYBOOK_COMPLETED,
                    sourceEventId,
                    sourceEventId,
                    reevaluationPayload
                );
                pendingOwnerReevaluationTrigger = new SessionTrigger(
                    SessionTriggerType.PLAYBOOK_COMPLETED,
                    platformTurn.turnId(),
                    sourceEventId,
                    reevaluationPayload
                );
            }
        }
        activePlaybookCompletion = null;
        activePlaybookWorkflow = null;
        activePlaybookWorkflowId = null;
        setSnapshot(copySnapshot(
            snapshot.sharedState(),
            null,
            snapshot.currentOwnerAgentId(),
            snapshot.ownerSwitchCountInTurn(),
            snapshot.agentTurnActive(),
            snapshot.sessionHumanHandoffActive(),
            pendingOwnerReevaluationTrigger != null,
            snapshot.draining()
        ));
        if (snapshot.draining()) {
            endWorkflowIfDrainingSafePointReached();
            return;
        }
        if (!snapshot.agentTurnActive() && previousRunId != null && pendingOwnerReevaluationTrigger != null) {
            finishTurn();
        }
    }

    private DecisionValidation validateDecision(
        AgentConfig owner,
        String currentOwnerAgentId,
        String activePlaybookRunId,
        int switchCount,
        boolean sessionHumanHandoffActive,
        AgentDecision decision
    ) {
        if (decision == null) {
            return DecisionValidation.rejected("decision_missing");
        }
        if (decision.action() != AgentDecisionAction.SECURITY_BLOCK && !owner.allowedActions().contains(decision.action())) {
            return DecisionValidation.rejected("action_not_allowed");
        }
        return switch (decision.action()) {
            case REPLY -> validateReplyDecision(decision);
            case NO_OP -> DecisionValidation.accepted(decision);
            case SWITCH_OWNER -> validateSwitchOwnerDecision(
                owner,
                decision,
                activePlaybookRunId,
                switchCount,
                sessionHumanHandoffActive
            );
            case RUN_PLAYBOOK -> validateRunPlaybookDecision(owner, decision, activePlaybookRunId, sessionHumanHandoffActive);
            case SESSION_HUMAN_HANDOFF -> sessionHumanHandoffActive
                ? DecisionValidation.idempotentSessionHandoff(decision)
                : DecisionValidation.accepted(decision);
            case SECURITY_BLOCK -> DecisionValidation.accepted(decision);
        };
    }

    private DecisionValidation validateReplyDecision(AgentDecision decision) {
        if (!hasMessageContent(decision.replyMessage())) {
            return DecisionValidation.rejected("reply_content_required");
        }
        return DecisionValidation.accepted(decision);
    }

    private Map<String, Object> humanHandoffStartedPayload(AgentDecision decision) {
        String operatorReason = decision.operatorReason();
        if (operatorReason == null || operatorReason.isBlank()) {
            return Map.of();
        }
        return Map.of("operatorReason", operatorReason.trim());
    }

    private DecisionValidation validateSwitchOwnerDecision(
        AgentConfig owner,
        AgentDecision decision,
        String activePlaybookRunId,
        int switchCount,
        boolean sessionHumanHandoffActive
    ) {
        String targetAgentId = decision.targetAgentId();
        if (targetAgentId == null || targetAgentId.isBlank()) {
            return DecisionValidation.rejected("switch_owner_target_agent_id_required");
        }
        if (sessionHumanHandoffActive) {
            return DecisionValidation.rejected("switch_owner_forbidden_during_handoff");
        }
        if (activePlaybookRunId != null) {
            return DecisionValidation.rejected("switch_owner_forbidden_while_playbook_active");
        }
        if (switchCount >= startRequest.assistant().ownerPolicy().maxOwnerSwitchesPerTurn()) {
            return DecisionValidation.rejected("owner_switch_limit_exceeded");
        }
        if (!owner.switchableOwnerAgentIds().contains(targetAgentId)) {
            return DecisionValidation.rejected("switch_owner_target_not_allowed");
        }
        AgentConfig targetAgent = agentsById.get(targetAgentId);
        if (targetAgent == null) {
            return DecisionValidation.rejected("switch_owner_target_not_found");
        }
        if (!targetAgent.canOwnSession()) {
            return DecisionValidation.rejected("switch_owner_target_cannot_own_session");
        }
        return DecisionValidation.accepted(decision);
    }

    private DecisionValidation validateRunPlaybookDecision(
        AgentConfig owner,
        AgentDecision decision,
        String activePlaybookRunId,
        boolean sessionHumanHandoffActive
    ) {
        String playbookId = decision.playbookId();
        Map<String, Object> playbookInput = decision.playbookInput();
        if (playbookId == null || playbookId.isBlank()) {
            return DecisionValidation.rejected("run_playbook_playbook_id_required");
        }
        if (playbookInput == null) {
            return DecisionValidation.rejected("run_playbook_input_required");
        }
        if (sessionHumanHandoffActive) {
            return DecisionValidation.rejected("run_playbook_forbidden_during_handoff");
        }
        if (activePlaybookRunId != null) {
            return DecisionValidation.rejected("run_playbook_forbidden_while_playbook_active");
        }
        if (!owner.playbookIds().contains(playbookId)) {
            return DecisionValidation.rejected("run_playbook_not_allowed_for_owner");
        }
        PlaybookConfig playbook = playbooksById.get(playbookId);
        if (playbook == null) {
            return DecisionValidation.rejected("run_playbook_not_found");
        }
        try {
            jsonSchemaValidator.validate(playbook.inputSchema(), playbookInput, "playbookInput");
            return DecisionValidation.accepted(decision);
        } catch (IllegalStateException error) {
            return DecisionValidation.rejected("run_playbook_input_schema_invalid");
        }
    }

    private boolean isSecurityBlocked(SecurityAssessment assessment) {
        return assessment != null && "BLOCK".equalsIgnoreCase(assessment.action());
    }

    private SecurityAssessment securityAssessmentOrDefault(SecurityAssessment assessment) {
        return assessment == null
            ? new SecurityAssessment("BLOCK", List.of("SECURITY_BLOCK"), "security_block_action", 1.0)
            : assessment;
    }

    private void emitSecurityBlocked(
        SecurityAssessment assessment,
        SessionTrigger trigger,
        String ownerAgentId,
        String activePlaybookRunId,
        String messageId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", assessment.action());
        payload.put("categories", assessment.categories());
        payload.put("reason", assessment.reason());
        payload.put("confidence", assessment.confidence());
        payload.put("triggerType", trigger.triggerType().name());
        payload.put("triggerTurnId", trigger.turnId());
        payload.put("triggerEventId", trigger.eventId());
        payload.put("inputMessageIds", trigger.payload().get("messageIds"));
        payload.put("ownerAgentId", ownerAgentId);
        emitSystemEventBackedReply(
            SessionEventType.USER_MESSAGE_SECURITY_BLOCKED,
            ownerAgentId,
            payload,
            activePlaybookRunId,
            ownerAgentId,
            textMessageInput(SECURITY_BLOCKED_REPLY),
            messageId
        );
    }

    private boolean canAcceptResumeSignal(String signalSessionId, PlaybookRun run, PlaybookWaitingType waitingType) {
        if (run == null || activePlaybookWorkflow == null) {
            return false;
        }
        if (signalSessionId == null || !snapshot.sessionId().equals(signalSessionId)) {
            return false;
        }
        if (!run.runId().equals(snapshot.activePlaybookRunId()) || run.status() != PlaybookRunStatus.WAITING) {
            return false;
        }
        if (run.waitingReason() == null) {
            return false;
        }
        return switch (waitingType) {
            case HUMAN_TASK -> run.waitingReason().startsWith("human_task:");
            case EXTERNAL_INTERACTION -> run.waitingReason().startsWith("external_interaction:");
        };
    }

    private boolean shouldAppendWaitingEvent(PlaybookRun previousRun, PlaybookRun newRun) {
        if (newRun.status() != PlaybookRunStatus.WAITING || newRun.waitingReason() == null) {
            return false;
        }
        return previousRun == null
            || previousRun.status() != PlaybookRunStatus.WAITING
            || !newRun.waitingReason().equals(previousRun.waitingReason());
    }

    private boolean shouldAppendResumedEvent(PlaybookRun previousRun, PlaybookRun newRun) {
        return previousRun != null
            && previousRun.status() == PlaybookRunStatus.WAITING
            && newRun.status() == PlaybookRunStatus.RUNNING
            && newRun.waitingReason() == null;
    }

    private void emitDecisionRejected(String reason, AgentDecision decision, String ownerAgentId, String activePlaybookRunId) {
        emitDecisionRejected(reason, decision, ownerAgentId, activePlaybookRunId, null);
    }

    private void emitDecisionRejected(
        String reason,
        AgentDecision decision,
        String ownerAgentId,
        String activePlaybookRunId,
        String messageId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rejectReason", reason);
        if (decision != null) {
            payload.put("action", decision.action().name());
            payload.put("targetAgentId", decision.targetAgentId());
            payload.put("playbookId", decision.playbookId());
            payload.put("playbookInput", decision.playbookInput());
        }
        emitSystemEventBackedReply(
            SessionEventType.AGENT_DECISION_REJECTED,
            ownerAgentId,
            payload,
            activePlaybookRunId,
            ownerAgentId,
            textMessageInput(DECISION_REJECTED_REPLY),
            messageId
        );
    }

    private void emitOwnerReply(
        String messageId,
        SessionMessageInput reply,
        SessionActorType actorType,
        String actorId,
        String activePlaybookRunId,
        String currentOwnerAgentId,
        String sourceEventId
    ) {
        if (!hasMessageContent(reply)) {
            return;
        }
        appendPlatformMessage(
            messageId,
            actorType == SessionActorType.SYSTEM ? SessionMessageRole.SYSTEM : SessionMessageRole.ASSISTANT,
            actorType == SessionActorType.SYSTEM ? SessionMessageSenderType.SYSTEM : SessionMessageSenderType.AGENT,
            actorId,
            resolveSenderName(
                actorType == SessionActorType.SYSTEM ? SessionMessageSenderType.SYSTEM : SessionMessageSenderType.AGENT,
                actorId
            ),
            reply,
            activePlaybookRunId,
            currentOwnerAgentId,
            sourceEventId
        );
    }

    private void emitSystemEventBackedReply(
        SessionEventType eventType,
        String actorId,
        Map<String, Object> payload,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId,
        SessionMessageInput reply,
        String messageId
    ) {
        if (!hasMessageContent(reply)) {
            appendEvent(eventType, SessionActorType.SYSTEM, actorId, payload, relatedPlaybookRunId, relatedOwnerAgentId);
            return;
        }
        String resolvedMessageId = messageId == null || messageId.isBlank() ? nextMessageId() : messageId;
        String eventId = appendEvent(
            eventType,
            SessionActorType.SYSTEM,
            actorId,
            payload,
            resolvedMessageId,
            relatedPlaybookRunId,
            relatedOwnerAgentId
        );
        emitOwnerReply(resolvedMessageId, reply, SessionActorType.SYSTEM, null, relatedPlaybookRunId, relatedOwnerAgentId, eventId);
    }

    private ActivePlaybookSummary activePlaybookSummary(String runId) {
        if (runId == null) {
            return null;
        }
        PlaybookRun run = playbookRunsById.get(runId);
        PlaybookConfig playbook = run == null ? null : playbooksById.get(run.playbookId());
        if (run == null) {
            return null;
        }
        return new ActivePlaybookSummary(
            run.runId(),
            run.playbookId(),
            playbook == null ? run.playbookId() : playbook.name(),
            run.status(),
            run.waitingReason(),
            run.result()
        );
    }

    private boolean shouldBootstrapTranscript(AgentConfig owner) {
        return !bootstrappedProviderContexts.contains(providerContextKey(owner));
    }

    private String providerContextKey(AgentConfig owner) {
        String providerType = owner != null && owner.model() != null && owner.model().providerType() != null
            ? owner.model().providerType().trim().toUpperCase()
            : "OPENAI_COMPATIBLE";
        return snapshot.sessionId() + "\u001f" + snapshot.currentOwnerAgentId() + "\u001f" + ownershipEpoch + "\u001f" + providerType;
    }

    private List<SessionMessage> bootstrapMessages(List<SessionMessage> turnMessages) {
        LinkedHashMap<String, SessionMessage> byId = new LinkedHashMap<>();
        for (SessionMessage message : messages) {
            byId.put(message.messageId(), message);
        }
        for (SessionMessage message : turnMessages) {
            byId.put(message.messageId(), message);
        }
        return orderedMessages(new ArrayList<>(byId.values()));
    }

    private List<SessionMessage> orderedMessages(List<SessionMessage> source) {
        return source == null
            ? List.of()
            : source.stream()
                .sorted(Comparator.comparingLong(SessionMessage::sequence))
                .toList();
    }

    private List<AgentRuntimeContextEntry> contextEntriesForRequest(
        boolean transcriptBootstrap,
        Map<String, Object> sharedState,
        String activePlaybookRunId
    ) {
        LinkedHashMap<String, AgentRuntimeContextEntry> entriesByKey = new LinkedHashMap<>();
        for (AgentRuntimeContextEntry entry : pendingContextEntries) {
            if (!emittedContextEntryKeys.contains(contextEntryKey(entry))) {
                entriesByKey.putIfAbsent(contextEntryKey(entry), entry);
            }
        }
        if (transcriptBootstrap) {
            putContextEntry(entriesByKey, sharedStateSnapshotContextEntry(sharedState));
            PlaybookRun run = activePlaybookRunId == null ? null : playbookRunsById.get(activePlaybookRunId);
            if (run != null) {
                putContextEntry(entriesByKey, activePlaybookSummaryContextEntry(run, null));
            }
        } else if (shouldIncludeSharedStateSnapshotForBoundary(entriesByKey.values())) {
            putContextEntry(entriesByKey, sharedStateSnapshotContextEntry(sharedState));
        }
        return entriesByKey.values().stream()
            .sorted(Comparator
                .comparing(AgentRuntimeContextEntry::occurredAt)
                .thenComparingLong(AgentRuntimeContextEntry::revision)
                .thenComparing(AgentRuntimeContextEntry::entryId))
            .toList();
    }

    private void markContextEntriesEmitted(List<AgentRuntimeContextEntry> contextEntries) {
        for (AgentRuntimeContextEntry entry : contextEntries) {
            emittedContextEntryKeys.add(contextEntryKey(entry));
        }
    }

    private void addContextEntry(AgentRuntimeContextEntry entry) {
        String key = contextEntryKey(entry);
        if (pendingContextEntries.stream().noneMatch(existing -> contextEntryKey(existing).equals(key))) {
            pendingContextEntries.add(entry);
        }
    }

    private void putContextEntry(Map<String, AgentRuntimeContextEntry> entriesByKey, AgentRuntimeContextEntry entry) {
        entriesByKey.putIfAbsent(contextEntryKey(entry), entry);
    }

    private String contextEntryKey(AgentRuntimeContextEntry entry) {
        return entry.entryType().name() + "\u001f" + entry.entryId() + "\u001f" + entry.revision();
    }

    private boolean shouldIncludeSharedStateSnapshotForBoundary(Collection<AgentRuntimeContextEntry> entries) {
        int patchCount = 0;
        int patchChars = 0;
        for (AgentRuntimeContextEntry entry : entries) {
            if (entry.entryType() != AgentRuntimeContextEntryType.SHARED_STATE_PATCH) {
                continue;
            }
            patchCount += 1;
            patchChars += String.valueOf(entry.data()).length();
            if (patchCount > SHARED_STATE_PATCH_SNAPSHOT_ENTRY_LIMIT || patchChars > SHARED_STATE_PATCH_SNAPSHOT_CHAR_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private AgentRuntimeContextEntry sharedStateSnapshotContextEntry(Map<String, Object> sharedState) {
        return new AgentRuntimeContextEntry(
            "shared-state-snapshot:" + snapshot.sessionId() + ":" + sharedStateRevision,
            AgentRuntimeContextEntryType.SHARED_STATE_SNAPSHOT,
            sharedStateRevision,
            sharedStateUpdatedAt == null ? sessionCreatedAt : sharedStateUpdatedAt,
            Map.of(
                "sessionId", snapshot.sessionId(),
                "sharedStateRevision", sharedStateRevision,
                "sharedState", sharedState == null ? Map.of() : sharedState
            )
        );
    }

    private void addSharedStatePatchContextEntry(
        Map<String, Object> previousSharedState,
        Map<String, Object> nextSharedState,
        Instant occurredAt
    ) {
        Map<String, Object> patch = changedSharedStatePatch(previousSharedState, nextSharedState);
        addContextEntry(new AgentRuntimeContextEntry(
            "shared-state-patch:" + snapshot.sessionId() + ":" + sharedStateRevision,
            AgentRuntimeContextEntryType.SHARED_STATE_PATCH,
            sharedStateRevision,
            occurredAt,
            Map.of(
                "sessionId", snapshot.sessionId(),
                "sharedStateRevision", sharedStateRevision,
                "patch", patch
            )
        ));
    }

    private Map<String, Object> changedSharedStatePatch(Map<String, Object> previousSharedState, Map<String, Object> nextSharedState) {
        Map<String, Object> previous = previousSharedState == null ? Map.of() : previousSharedState;
        Map<String, Object> next = nextSharedState == null ? Map.of() : nextSharedState;
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(previous.keySet());
        keys.addAll(next.keySet());
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String key : keys) {
            Object previousValue = previous.get(key);
            Object nextValue = next.get(key);
            if (!Objects.equals(previousValue, nextValue)) {
                patch.put(key, nextValue);
            }
        }
        return patch;
    }

    private void addSessionEventContextEntry(SessionEvent event) {
        if (event == null || !modelRelevantSessionEvent(event.eventType())) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("eventType", event.eventType().name());
        data.put("actorType", event.actorType().name());
        data.put("actorId", event.actorId());
        data.put("payload", event.payload());
        data.put("relatedMessageId", event.relatedMessageId());
        data.put("relatedPlaybookRunId", event.relatedPlaybookRunId());
        data.put("relatedOwnerAgentId", event.relatedOwnerAgentId());
        addContextEntry(new AgentRuntimeContextEntry(
            event.eventId(),
            AgentRuntimeContextEntryType.SESSION_EVENT,
            event.sequence(),
            event.createdAt(),
            data
        ));
    }

    private boolean modelRelevantSessionEvent(SessionEventType eventType) {
        return eventType != null;
    }

    private void addActivePlaybookSummaryContextEntry(PlaybookRun run, String eventId) {
        if (run == null) {
            return;
        }
        addContextEntry(activePlaybookSummaryContextEntry(run, eventId));
    }

    private AgentRuntimeContextEntry activePlaybookSummaryContextEntry(PlaybookRun run, String eventId) {
        SessionEvent event = eventId == null ? null : findEvent(eventId);
        long revision = event == null ? Math.max(1L, run.updatedAt().toEpochMilli()) : event.sequence();
        Instant occurredAt = event == null ? run.updatedAt() : event.createdAt();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playbookId", run.playbookId());
        data.put("runId", run.runId());
        data.put("ownerAgentId", run.ownerAgentId());
        data.put("status", run.status().name());
        data.put("waitingReason", run.waitingReason());
        data.put("latestResult", run.result());
        data.put("failureReason", run.failureReason());
        data.put("sourceEventId", eventId);
        return new AgentRuntimeContextEntry(
            "active-playbook:" + run.runId() + ":" + revision,
            AgentRuntimeContextEntryType.ACTIVE_PLAYBOOK_SUMMARY,
            revision,
            occurredAt,
            data
        );
    }

    private SessionEvent findEvent(String eventId) {
        if (eventId == null) {
            return null;
        }
        for (SessionEvent event : events) {
            if (event.eventId().equals(eventId)) {
                return event;
            }
        }
        return null;
    }

    private void appendPersistedTurnMessages(List<SessionMessage> turnMessages) {
        for (SessionMessage message : turnMessages) {
            if (messages.stream().noneMatch(existing -> existing.messageId().equals(message.messageId()))) {
                messages.add(message);
            }
        }
    }

    private String appendPlatformMessage(
        String messageId,
        SessionMessageRole role,
        SessionMessageSenderType senderType,
        String senderId,
        String senderName,
        SessionMessageInput message,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId,
        String sourceEventId
    ) {
        if (activeTurnId == null || activeTurnId.isBlank()) {
            throw new IllegalStateException("platform message append requires an active turn id");
        }
        String resolvedMessageId = messageId == null || messageId.isBlank() ? nextMessageId() : messageId;
        Instant now = now();
        List<SessionMessage> appended = persistenceActivities.appendSessionMessages(
            snapshot.sessionId(),
            activeTurnId,
            List.of(new SessionPersistenceActivities.SessionMessageAppendRecord(
                resolvedMessageId,
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                now,
                role,
                new SessionMessageSender(senderType, senderId, senderName),
                SessionMessageStatus.SENT,
                message.blocks(),
                message.metadata(),
                relatedPlaybookRunId,
                relatedOwnerAgentId,
                sourceEventId,
                now,
                now
            ))
        );
        if (appended.isEmpty()) {
            throw new IllegalStateException("platform message append returned no message: " + resolvedMessageId);
        }
        SessionMessage item = appended.getFirst();
        if (messages.stream().noneMatch(existing -> existing.messageId().equals(item.messageId()))) {
            messages.add(item);
        }
        return item.messageId();
    }

    private void requireValidUserTurn(UserTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn is required");
        }
        if (turn.turnId() == null || turn.turnId().isBlank()) {
            throw new IllegalArgumentException("turnId is required");
        }
        if (turn.messages() == null || turn.messages().isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        for (SessionMessage message : turn.messages()) {
            if (message == null) {
                throw new IllegalArgumentException("turn message is required");
            }
            if (!turn.turnId().equals(message.turnId())) {
                throw new IllegalArgumentException("turn message does not belong to turn");
            }
            if (snapshot != null && !snapshot.sessionId().equals(message.sessionId())) {
                throw new IllegalArgumentException("turn message does not belong to session");
            }
            if (!hasMessageContent(new SessionMessageInput(message.blocks(), message.metadata()))) {
                throw new IllegalArgumentException("message content required");
            }
        }
    }

    private String appendPlatformMessage(
        SessionMessageRole role,
        SessionMessageSenderType senderType,
        String senderId,
        String senderName,
        SessionMessageInput message,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId,
        String sourceEventId
    ) {
        return appendPlatformMessage(
            null,
            role,
            senderType,
            senderId,
            senderName,
            message,
            relatedPlaybookRunId,
            relatedOwnerAgentId,
            sourceEventId
        );
    }

    private String appendEvent(
        SessionEventType eventType,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        return appendEvent(eventType, actorType, actorId, payload, null, relatedPlaybookRunId, relatedOwnerAgentId);
    }

    private String appendEvent(
        String eventId,
        SessionEventType eventType,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        return appendEvent(eventId, eventType, actorType, actorId, payload, null, relatedPlaybookRunId, relatedOwnerAgentId);
    }

    private String appendEvent(
        SessionEventType eventType,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedMessageId,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        return appendEvent(
            "session-event-" + Workflow.randomUUID(),
            eventType,
            actorType,
            actorId,
            payload,
            relatedMessageId,
            relatedPlaybookRunId,
            relatedOwnerAgentId
        );
    }

    private String appendEvent(
        String eventId,
        SessionEventType eventType,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedMessageId,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        if (events.stream().anyMatch(existing -> existing.eventId().equals(eventId))) {
            return eventId;
        }
        SessionEvent event = new SessionEvent(
            eventId,
            snapshot.sessionId(),
            events.size() + 1L,
            eventType,
            now(),
            actorType,
            actorId,
            payload,
            relatedMessageId,
            relatedPlaybookRunId,
            relatedOwnerAgentId
        );
        events.add(event);
        addSessionEventContextEntry(event);
        persistenceActivities.appendEvent(event);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("sessionEventId", event.eventId());
        auditPayload.put("sessionEventType", event.eventType().name());
        auditPayload.put("actorType", event.actorType().name());
        auditPayload.put("relatedPlaybookRunId", event.relatedPlaybookRunId());
        auditPayload.put("relatedOwnerAgentId", event.relatedOwnerAgentId());
        appendPlatformEvent("SESSION_EVENT_RECORDED", "SESSION", snapshot.sessionId(), event.actorId(), auditPayload, event.createdAt());
        return eventId;
    }

    private SessionPersistenceActivities.PlatformTurnAllocation allocatePlatformTurn(
        SessionTriggerType triggerType,
        String dedupKey,
        String sourceEventId,
        Map<String, Object> metadata
    ) {
        return persistenceActivities.allocatePlatformTurn(
            snapshot.sessionId(),
            triggerType.name(),
            dedupKey,
            sourceEventId,
            metadata == null ? Map.of() : metadata
        );
    }

    private void persistPlaybookRun(PlaybookRun newRun, PlaybookRun previousRun, String sourceEventId, String actorId) {
        playbookRunsById.put(newRun.runId(), newRun);
        persistenceActivities.savePlaybookRun(newRun);
        if (!shouldAppendPlaybookRunAuditEvent(previousRun, newRun)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", newRun.sessionId());
        payload.put("playbookId", newRun.playbookId());
        payload.put("ownerAgentId", newRun.ownerAgentId());
        payload.put("previousStatus", previousRun == null ? null : previousRun.status().name());
        payload.put("currentStatus", newRun.status().name());
        payload.put("waitingReason", newRun.waitingReason());
        payload.put("parentSessionEventId", newRun.parentSessionEventId());
        payload.put("sourceEventId", sourceEventId);
        appendPlatformEvent("PLAYBOOK_RUN_STATUS_CHANGED", "PLAYBOOK_RUN", newRun.runId(), actorId, payload, newRun.updatedAt());
    }

    private boolean shouldAppendPlaybookRunAuditEvent(PlaybookRun previousRun, PlaybookRun newRun) {
        return previousRun == null
            || previousRun.status() != newRun.status()
            || !Objects.equals(previousRun.waitingReason(), newRun.waitingReason());
    }

    private void appendPlatformEvent(
        String eventType,
        String aggregateType,
        String aggregateId,
        String actorId,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        persistenceActivities.appendPlatformEvent(new SessionPersistenceActivities.PlatformEventRecord(
            "platform-event-" + Workflow.randomUUID(),
            eventType,
            aggregateType,
            aggregateId,
            actorId,
            payload,
            occurredAt
        ));
    }

    private void appendPrivacyMappingAuditEvents(
        String actorId,
        com.lynxus.contracts.session.SessionContracts.PrivacyMappingTelemetry telemetry
    ) {
        Map<String, Object> basePayload = new LinkedHashMap<>();
        basePayload.put("sessionId", snapshot.sessionId());
        basePayload.put("agentId", actorId);
        basePayload.put("privacyModelResourceId", telemetry.privacyModelResourceId());
        basePayload.put("privacyModelResourceName", telemetry.privacyModelResourceName());
        basePayload.put("entityTypeBreakdown", telemetry.entityTypeBreakdown());
        basePayload.put("placeholderCount", telemetry.placeholderCount());
        basePayload.put("unresolvedPlaceholderCount", telemetry.unresolvedPlaceholderCount());
        basePayload.put("blockedEventCount", telemetry.blockedEventCount());
        basePayload.put("sanitizeCountByChannel", telemetry.sanitizeCountByChannel());
        basePayload.put("restoreCountByChannel", telemetry.restoreCountByChannel());
        basePayload.put("lastProcessedAt", telemetry.lastProcessedAt() == null ? null : telemetry.lastProcessedAt().toString());

        int sanitizeCount = telemetry.sanitizeCountByChannel().values().stream().mapToInt(Integer::intValue).sum();
        int restoreCount = telemetry.restoreCountByChannel().values().stream().mapToInt(Integer::intValue).sum();
        if (telemetry.placeholderCount() > 0) {
            appendPlatformEvent(
                "PRIVACY_MAPPING_CREATED",
                "SESSION_PRIVACY_MAPPING",
                snapshot.sessionId(),
                actorId,
                basePayload,
                now()
            );
        }
        if (sanitizeCount > 0) {
            appendPlatformEvent(
                "PRIVACY_OUTBOUND_SANITIZED",
                "SESSION_PRIVACY_MAPPING",
                snapshot.sessionId(),
                actorId,
                basePayload,
                now()
            );
        }
        if (restoreCount > 0) {
            appendPlatformEvent(
                "PRIVACY_INBOUND_RESTORED",
                "SESSION_PRIVACY_MAPPING",
                snapshot.sessionId(),
                actorId,
                basePayload,
                now()
            );
        }
        if (telemetry.blockedEventCount() > 0 || telemetry.unresolvedPlaceholderCount() > 0) {
            appendPlatformEvent(
                "PRIVACY_MAPPING_BLOCKED",
                "SESSION_PRIVACY_MAPPING",
                snapshot.sessionId(),
                actorId,
                basePayload,
                now()
            );
        }
    }

    private void persistLlmUsage(
        SessionTrigger trigger,
        String turnExecutionId,
        String agentId,
        String playbookRunId,
        List<com.lynxus.contracts.session.SessionContracts.LlmUsageEntry> usageEntries
    ) {
        String triggerReferenceId = trigger.eventId() == null || trigger.eventId().isBlank()
            ? trigger.turnId()
            : trigger.eventId();
        persistenceActivities.appendLlmUsage(usageEntries.stream()
            .map(entry -> new SessionPersistenceActivities.LlmUsageRecord(
                stableLlmUsageId(turnExecutionId, entry.callSequence()),
                entry.sourceType().name(),
                snapshot.sessionId(),
                triggerReferenceId,
                trigger.triggerType().name(),
                playbookRunId,
                startRequest.scenarioId(),
                startRequest.customerId(),
                startRequest.assistant().assistantId(),
                snapshot.assistantReleaseVersion(),
                agentId,
                entry.providerType(),
                entry.modelResourceId(),
                entry.modelResourceVersionId(),
                entry.modelId(),
                entry.usageAvailable(),
                entry.promptTokens(),
                entry.completionTokens(),
                entry.totalTokens(),
                entry.rawUsage(),
                entry.callSequence(),
                entry.toolLoopStep(),
                entry.occurredAt() == null ? now() : entry.occurredAt()
            ))
            .toList());
    }

    private String stableLlmUsageId(String turnExecutionId, int callSequence) {
        return sha256Hex(snapshot.sessionId() + "|" + turnExecutionId + "|" + callSequence);
    }

    private static boolean hasMessageContent(SessionMessageInput input) {
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static SessionMessageInput textMessageInput(String text) {
        return new SessionMessageInput(
            List.of(Map.of("type", "TEXT", "text", text)),
            Map.of()
        );
    }

    private static String nextMessageId() {
        return "session-message-" + Workflow.randomUUID();
    }

    private String resolveSenderName(SessionMessageSenderType senderType, String senderId) {
        return switch (senderType) {
            case CUSTOMER, HUMAN_OPERATOR -> senderId == null || senderId.isBlank() ? senderType.name() : senderId;
            case AGENT -> {
                AgentConfig agent = senderId == null ? null : agentsById.get(senderId);
                yield agent == null ? (senderId == null || senderId.isBlank() ? "Agent" : senderId) : agent.name();
            }
            case SYSTEM -> SYSTEM_SENDER_NAME;
        };
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
            throw new IllegalStateException("failed to hash llm usage key", error);
        }
    }

    private SessionSnapshot copySnapshot(
        Map<String, Object> sharedState,
        String activePlaybookRunId,
        String currentOwnerAgentId,
        int ownerSwitchCountInTurn,
        boolean agentTurnActive,
        boolean sessionHumanHandoffActive,
        boolean pendingOwnerReevaluation,
        boolean draining
    ) {
        return new SessionSnapshot(
            snapshot.sessionId(),
            snapshot.assistantId(),
            snapshot.assistantReleaseVersion(),
            snapshot.primaryAgentId(),
            currentOwnerAgentId,
            ownerSwitchCountInTurn,
            sharedState,
            activePlaybookRunId,
            agentTurnActive,
            sessionHumanHandoffActive,
            pendingOwnerReevaluation,
            draining,
            resolveIdleDeadline(activePlaybookRunId, agentTurnActive, sessionHumanHandoffActive)
        );
    }

    private void setSnapshot(SessionSnapshot nextSnapshot) {
        this.snapshot = nextSnapshot;
        persistSession(statusForSnapshot(false), now());
    }

    private void persistSession(String status, Instant updatedAt) {
        persistenceActivities.saveSession(new SessionPersistenceActivities.SessionRecord(
            snapshot.sessionId(),
            startRequest.scenarioId(),
            startRequest.sessionTitle(),
            startRequest.customerId(),
            snapshot.assistantId(),
            startRequest.assistant().assistantName(),
            snapshot.assistantReleaseVersion(),
            status,
            snapshot.primaryAgentId(),
            snapshot.currentOwnerAgentId(),
            snapshot.activePlaybookRunId(),
            snapshot.agentTurnActive(),
            snapshot.sessionHumanHandoffActive(),
            snapshot.pendingOwnerReevaluation(),
            snapshot.draining(),
            snapshot.sharedState(),
            sharedStateRevision,
            snapshot.idleDeadline(),
            sessionCreatedAt,
            updatedAt,
            "ENDED".equals(status) ? updatedAt : null
        ));
    }

    private String statusForSnapshot(boolean workflowEnded) {
        if (workflowEnded) {
            return "ENDED";
        }
        if (snapshot.draining()) {
            return "DRAINING";
        }
        if (snapshot.agentTurnActive() || snapshot.activePlaybookRunId() != null || snapshot.sessionHumanHandoffActive()) {
            return "ACTIVE";
        }
        return "IDLE";
    }

    private void applyGuardrails() {
        refreshDrainingState();
    }

    private boolean guardrailExceeded() {
        Duration maxWorkflowAge = startRequest.assistant().sessionPolicy().maxWorkflowAge();
        int maxWorkflowHistoryEvents = startRequest.assistant().sessionPolicy().maxWorkflowHistoryEvents();
        boolean ageExceeded = maxWorkflowAge != null
            && !maxWorkflowAge.isZero()
            && !maxWorkflowAge.isNegative()
            && Workflow.currentTimeMillis() - workflowStartedAt.toEpochMilli() >= maxWorkflowAge.toMillis();
        boolean historyExceeded = maxWorkflowHistoryEvents > 0
            && Workflow.getInfo().getHistoryLength() >= maxWorkflowHistoryEvents;
        return ageExceeded || historyExceeded;
    }

    private void refreshDrainingState() {
        if (snapshot.draining() || !guardrailExceeded()) {
            return;
        }
        setSnapshot(new SessionSnapshot(
            snapshot.sessionId(),
            snapshot.assistantId(),
            snapshot.assistantReleaseVersion(),
            snapshot.primaryAgentId(),
            snapshot.currentOwnerAgentId(),
            snapshot.ownerSwitchCountInTurn(),
            snapshot.sharedState(),
            snapshot.activePlaybookRunId(),
            snapshot.agentTurnActive(),
            snapshot.sessionHumanHandoffActive(),
            snapshot.pendingOwnerReevaluation(),
            true,
            snapshot.idleDeadline()
        ));
        endWorkflowIfDrainingSafePointReached();
    }

    private boolean shouldEndIdleSession() {
        return !hasNonIdleExecution()
            && snapshot.idleDeadline() != null
            && !snapshot.draining()
            && Workflow.currentTimeMillis() >= snapshot.idleDeadline().toEpochMilli();
    }

    private boolean shouldEndDrainingSession() {
        return snapshot.draining() && !hasNonIdleExecution();
    }

    private void endWorkflowIfDrainingSafePointReached() {
        if (shouldEndDrainingSession()) {
            ended = true;
        }
    }

    private boolean hasNonIdleExecution() {
        return snapshot.agentTurnActive() || snapshot.activePlaybookRunId() != null || snapshot.sessionHumanHandoffActive();
    }

    private Duration nextLifecycleCheckDelay() {
        long nextMillis = Duration.ofMinutes(1).toMillis();
        if (!hasNonIdleExecution() && snapshot.idleDeadline() != null) {
            nextMillis = Math.min(nextMillis, positiveDelayMillis(snapshot.idleDeadline().toEpochMilli() - Workflow.currentTimeMillis()));
        }
        Duration maxWorkflowAge = startRequest.assistant().sessionPolicy().maxWorkflowAge();
        if (maxWorkflowAge != null && !maxWorkflowAge.isZero() && !maxWorkflowAge.isNegative()) {
            long maxAgeDeadline = workflowStartedAt.toEpochMilli() + maxWorkflowAge.toMillis();
            nextMillis = Math.min(nextMillis, positiveDelayMillis(maxAgeDeadline - Workflow.currentTimeMillis()));
        }
        return Duration.ofMillis(nextMillis);
    }

    private long positiveDelayMillis(long millis) {
        return Math.max(1L, millis);
    }

    private Instant resolveIdleDeadline(
        String activePlaybookRunId,
        boolean agentTurnActive,
        boolean sessionHumanHandoffActive
    ) {
        if (agentTurnActive || activePlaybookRunId != null || sessionHumanHandoffActive) {
            return snapshot.idleDeadline();
        }
        if (hasNonIdleExecution() || snapshot.idleDeadline() == null) {
            return nextIdleDeadline();
        }
        return snapshot.idleDeadline();
    }

    private Instant nextIdleDeadline() {
        Duration idleTimeout = startRequest.assistant().sessionPolicy().idleTimeout();
        Duration effectiveIdleTimeout = idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()
            ? Duration.ofMinutes(30)
            : idleTimeout;
        return Instant.ofEpochMilli(Workflow.currentTimeMillis() + effectiveIdleTimeout.toMillis());
    }

    private Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
}
