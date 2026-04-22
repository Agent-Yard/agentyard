package com.lynxus.worker.session;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionContracts.ActivePlaybookSummary;
import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentDecision;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnResult;
import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
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
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionTrigger;
import com.lynxus.contracts.session.SessionContracts.SessionTriggerType;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public class SessionWorkflowImpl implements SessionWorkflow {
    private static final String DECISION_REJECTED_REPLY = "当前无法完成该操作，请稍后再试";
    private static final String TURN_FAILED_REPLY = "当前处理遇到问题，请稍后再试";
    private static final int RECENT_EVENT_WINDOW = 20;

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
    private final List<SessionEvent> events = new ArrayList<>();
    private Promise<PlaybookRun> activePlaybookCompletion;
    private PlaybookWorkflow activePlaybookWorkflow;
    private String activePlaybookWorkflowId;
    private SessionTrigger pendingOwnerReevaluationTrigger;
    private Instant workflowStartedAt;
    private Instant sessionCreatedAt;
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
        this.events.clear();
        this.activePlaybookCompletion = null;
        this.activePlaybookWorkflow = null;
        this.activePlaybookWorkflowId = null;
        this.pendingOwnerReevaluationTrigger = null;
        this.workflowStartedAt = now();
        this.sessionCreatedAt = workflowStartedAt;
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
    public SessionUserMessageUpdateResult submitUserMessage(UserMessage message) {
        refreshDrainingState();
        if (snapshot.draining() || ended) {
            return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.REJECTED, snapshot.sessionId(), "workflow draining");
        }
        if (snapshot.agentTurnActive()) {
            return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.BUSY, snapshot.sessionId(), "agent turn active");
        }
        String eventId = appendEvent(
            SessionEventType.USER_MESSAGE,
            SessionActorType.USER,
            message.customerId(),
            Map.of("text", message.content(), "payload", message.payload()),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId()
        );
        if (snapshot.sessionHumanHandoffActive()) {
            return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.ACCEPTED, snapshot.sessionId(), "session human handoff active");
        }
        executeTurn(new SessionTrigger(
            SessionTriggerType.USER_MESSAGE,
            eventId,
            Map.of("text", message.content(), "payload", message.payload(), "customerId", message.customerId())
        ));
        return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.ACCEPTED, snapshot.sessionId(), null);
    }

    @Override
    public void humanResume(HumanResumeSignal signal) {
        if (signal == null) {
            return;
        }
        PlaybookRun run = playbookRunsById.get(signal.playbookRunId());
        if (!canAcceptResumeSignal(signal.sessionId(), run, PlaybookWaitingType.HUMAN_TASK)) {
            return;
        }
        appendEvent(
            SessionEventType.HUMAN_RESUME_RECEIVED,
            SessionActorType.HUMAN_OPERATOR,
            null,
            Map.of("playbookRunId", signal.playbookRunId(), "payload", signal.payload()),
            signal.playbookRunId(),
            snapshot.currentOwnerAgentId()
        );
        activePlaybookWorkflow.resume(new PlaybookResumeSignal(signal.playbookRunId(), PlaybookResumeSource.HUMAN, signal.payload()));
    }

    @Override
    public void externalCallback(ExternalCallbackSignal signal) {
        if (signal == null) {
            return;
        }
        PlaybookRun run = playbookRunsById.get(signal.playbookRunId());
        if (!canAcceptResumeSignal(signal.sessionId(), run, PlaybookWaitingType.EXTERNAL_INTERACTION)) {
            return;
        }
        appendEvent(
            SessionEventType.EXTERNAL_CALLBACK_RECEIVED,
            SessionActorType.EXTERNAL_SYSTEM,
            null,
            Map.of("playbookRunId", signal.playbookRunId(), "payload", signal.payload()),
            signal.playbookRunId(),
            snapshot.currentOwnerAgentId()
        );
        activePlaybookWorkflow.resume(new PlaybookResumeSignal(signal.playbookRunId(), PlaybookResumeSource.EXTERNAL_SYSTEM, signal.payload()));
    }

    @Override
    public void endHumanHandoff() {
        if (!snapshot.sessionHumanHandoffActive()) {
            return;
        }
        appendEvent(
            SessionEventType.SESSION_HUMAN_HANDOFF_ENDED,
            SessionActorType.SYSTEM,
            null,
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
        if (!snapshot.sessionHumanHandoffActive() || signal == null || signal.content() == null || signal.content().isBlank()) {
            return;
        }
        appendEvent(
            SessionEventType.HUMAN_OPERATOR_REPLY,
            SessionActorType.HUMAN_OPERATOR,
            signal.operatorId(),
            Map.of("text", signal.content(), "payload", signal.payload()),
            snapshot.activePlaybookRunId(),
            snapshot.currentOwnerAgentId()
        );
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
                null,
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

    private void executeTurn(SessionTrigger trigger) {
        int switchCount = 0;
        String currentOwnerAgentId = snapshot.currentOwnerAgentId();
        String activePlaybookRunId = snapshot.activePlaybookRunId();
        boolean sessionHumanHandoffActive = snapshot.sessionHumanHandoffActive();
        Map<String, Object> sharedState = snapshot.sharedState();
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
            try {
                outcome = activities.executeTurn(new AgentTurnRequest(
                    snapshot.sessionId(),
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
                    recentEvents()
                ));
            } catch (RuntimeException error) {
                appendEvent(
                    SessionEventType.AGENT_TURN_FAILED,
                    SessionActorType.SYSTEM,
                    currentOwnerAgentId,
                    Map.of("reason", error.getMessage() == null ? "agent turn failed" : error.getMessage(), "triggerType", trigger.triggerType().name()),
                    activePlaybookRunId,
                    currentOwnerAgentId
                );
                emitOwnerReply(TURN_FAILED_REPLY, SessionActorType.SYSTEM, null, activePlaybookRunId, currentOwnerAgentId);
                break;
            }

            if (outcome != null && outcome.llmUsage() != null && !outcome.llmUsage().isEmpty()) {
                persistLlmUsage(trigger, currentOwnerAgentId, activePlaybookRunId, outcome.llmUsage());
            }
            if (outcome == null || !outcome.success() || outcome.result() == null) {
                String failureReason = outcome == null || outcome.failureReason() == null || outcome.failureReason().isBlank()
                    ? "agent turn failed"
                    : outcome.failureReason();
                appendEvent(
                    SessionEventType.AGENT_TURN_FAILED,
                    SessionActorType.SYSTEM,
                    currentOwnerAgentId,
                    Map.of("reason", failureReason, "triggerType", trigger.triggerType().name()),
                    activePlaybookRunId,
                    currentOwnerAgentId
                );
                emitOwnerReply(TURN_FAILED_REPLY, SessionActorType.SYSTEM, null, activePlaybookRunId, currentOwnerAgentId);
                break;
            }
            AgentTurnResult result = outcome.result();
            sharedState = result == null ? sharedState : result.sharedState();
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
                    activePlaybookRunId
                );
                break;
            }
            AgentDecision decision = validation.decision();

            if (decision.action() == AgentDecisionAction.REPLY) {
                emitOwnerReply(decision.replyContent(), SessionActorType.AGENT, currentOwnerAgentId, activePlaybookRunId, currentOwnerAgentId);
                break;
            }
            if (decision.action() == AgentDecisionAction.NO_REPLY) {
                break;
            }

            if (shouldEmitAccompanyingReply(decision)) {
                emitOwnerReply(decision.accompanyingReply(), SessionActorType.AGENT, currentOwnerAgentId, activePlaybookRunId, currentOwnerAgentId);
            }

            if (decision.action() == AgentDecisionAction.SWITCH_OWNER) {
                String previousOwnerAgentId = currentOwnerAgentId;
                currentOwnerAgentId = decision.targetAgentId();
                switchCount += 1;
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
                    Map.of(),
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
        finishTurn();
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
        executeTurn(trigger);
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
            if (!snapshot.sessionHumanHandoffActive() && !snapshot.draining()) {
                Map<String, Object> reevaluationPayload = new LinkedHashMap<>();
                reevaluationPayload.put("playbookRunId", completedRun.runId());
                reevaluationPayload.put("status", completedRun.status().name());
                reevaluationPayload.put("result", completedRun.result());
                if (completedRun.failureReason() != null) {
                    reevaluationPayload.put("failureReason", completedRun.failureReason());
                }
                pendingOwnerReevaluationTrigger = new SessionTrigger(
                    SessionTriggerType.PLAYBOOK_COMPLETED,
                    lastEventId(),
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
        if (!owner.allowedActions().contains(decision.action())) {
            return DecisionValidation.rejected("action_not_allowed");
        }
        return switch (decision.action()) {
            case REPLY -> validateReplyDecision(decision);
            case NO_REPLY -> DecisionValidation.accepted(decision);
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
        };
    }

    private DecisionValidation validateReplyDecision(AgentDecision decision) {
        if (decision.replyContent() == null || decision.replyContent().isBlank()) {
            return DecisionValidation.rejected("reply_content_required");
        }
        return DecisionValidation.accepted(decision);
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

    private boolean shouldEmitAccompanyingReply(AgentDecision decision) {
        return decision.accompanyingReply() != null
            && !decision.accompanyingReply().isBlank()
            && (
                decision.action() == AgentDecisionAction.SWITCH_OWNER
                    || decision.action() == AgentDecisionAction.RUN_PLAYBOOK
                    || decision.action() == AgentDecisionAction.SESSION_HUMAN_HANDOFF
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rejectReason", reason);
        if (decision != null) {
            payload.put("action", decision.action().name());
            payload.put("targetAgentId", decision.targetAgentId());
            payload.put("playbookId", decision.playbookId());
            payload.put("playbookInput", decision.playbookInput());
        }
        appendEvent(
            SessionEventType.AGENT_DECISION_REJECTED,
            SessionActorType.SYSTEM,
            ownerAgentId,
            payload,
            activePlaybookRunId,
            ownerAgentId
        );
        emitOwnerReply(DECISION_REJECTED_REPLY, SessionActorType.SYSTEM, null, activePlaybookRunId, ownerAgentId);
    }

    private void emitOwnerReply(
        String reply,
        SessionActorType actorType,
        String actorId,
        String activePlaybookRunId,
        String currentOwnerAgentId
    ) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        appendEvent(
            SessionEventType.OWNER_REPLY,
            actorType,
            actorId,
            Map.of("text", reply),
            activePlaybookRunId,
            currentOwnerAgentId
        );
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

    private List<SessionEvent> recentEvents() {
        int start = Math.max(0, events.size() - RECENT_EVENT_WINDOW);
        return List.copyOf(events.subList(start, events.size()));
    }

    private String appendEvent(
        SessionEventType eventType,
        SessionActorType actorType,
        String actorId,
        Map<String, Object> payload,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId
    ) {
        String eventId = "session-event-" + Workflow.randomUUID();
        SessionEvent event = new SessionEvent(
            eventId,
            snapshot.sessionId(),
            events.size() + 1L,
            eventType,
            now(),
            actorType,
            actorId,
            payload,
            relatedPlaybookRunId,
            relatedOwnerAgentId
        );
        events.add(event);
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
        String agentId,
        String playbookRunId,
        List<com.lynxus.contracts.session.SessionContracts.LlmUsageEntry> usageEntries
    ) {
        persistenceActivities.appendLlmUsage(usageEntries.stream()
            .map(entry -> new SessionPersistenceActivities.LlmUsageRecord(
                stableLlmUsageId(trigger.eventId(), entry.callSequence()),
                entry.sourceType().name(),
                snapshot.sessionId(),
                trigger.eventId(),
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

    private String stableLlmUsageId(String triggerEventId, int callSequence) {
        return sha256Hex(snapshot.sessionId() + "|" + triggerEventId + "|" + callSequence);
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

    private String lastEventId() {
        return events.isEmpty() ? null : events.getLast().eventId();
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
