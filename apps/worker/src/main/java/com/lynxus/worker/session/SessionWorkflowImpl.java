package com.lynxus.worker.session;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionContracts.ActivePlaybookSummary;
import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentDecision;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public class SessionWorkflowImpl implements SessionWorkflow {
    private static final String DECISION_REJECTED_REPLY = "当前无法完成该操作，请稍后再试";
    private static final String TURN_FAILED_REPLY = "当前处理遇到问题，请稍后再试";
    private static final int RECENT_EVENT_WINDOW = 20;

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
        if (snapshot.agentTurnActive()) {
            return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.BUSY, snapshot.sessionId(), "agent turn active");
        }
        if (snapshot.draining()) {
            return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.REJECTED, snapshot.sessionId(), "workflow draining");
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
        playbookRunsById.put(update.run().runId(), update.run());
        persistenceActivities.savePlaybookRun(update.run());
        if (!update.run().runId().equals(snapshot.activePlaybookRunId())) {
            return;
        }
        if (update.progressType() == PlaybookProgressType.WAITING && shouldAppendWaitingEvent(previousRun, update.run())) {
            appendEvent(
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
            appendEvent(
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
            AgentTurnResult result;
            try {
                result = activities.executeTurn(new AgentTurnRequest(
                    snapshot.sessionId(),
                    snapshot.assistantId(),
                    snapshot.assistantReleaseVersion(),
                    owner,
                    startRequest.agents(),
                    startRequest.playbooks(),
                    activePlaybookSummary(activePlaybookRunId),
                    sharedState,
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

            sharedState = result == null ? sharedState : result.sharedState();
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
            AgentDecision decision = result == null ? null : sanitizeDecision(owner, currentOwnerAgentId, activePlaybookRunId, switchCount, result.decision());
            if (decision == null) {
                emitDecisionRejected("agent decision rejected by runtime guardrail", result == null ? null : result.decision(), currentOwnerAgentId, activePlaybookRunId);
                break;
            }

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
                if (sessionHumanHandoffActive) {
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
        persistenceActivities.savePlaybookRun(run);
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
            playbookRunsById.put(completedRun.runId(), completedRun);
            persistenceActivities.savePlaybookRun(completedRun);
            appendEvent(
                SessionEventType.PLAYBOOK_COMPLETED,
                SessionActorType.SYSTEM,
                null,
                Map.of("status", completedRun.status().name(), "result", completedRun.result(), "failureReason", completedRun.failureReason()),
                completedRun.runId(),
                completedRun.ownerAgentId()
            );
            if (!snapshot.sessionHumanHandoffActive()) {
                pendingOwnerReevaluationTrigger = new SessionTrigger(
                    SessionTriggerType.PLAYBOOK_COMPLETED,
                    lastEventId(),
                    Map.of("playbookRunId", completedRun.runId(), "status", completedRun.status().name(), "result", completedRun.result(), "failureReason", completedRun.failureReason())
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
        if (!snapshot.agentTurnActive() && previousRunId != null && pendingOwnerReevaluationTrigger != null) {
            finishTurn();
        }
    }

    private AgentDecision sanitizeDecision(
        AgentConfig owner,
        String currentOwnerAgentId,
        String activePlaybookRunId,
        int switchCount,
        AgentDecision decision
    ) {
        if (decision == null || !owner.allowedActions().contains(decision.action())) {
            return null;
        }
        return switch (decision.action()) {
            case REPLY -> decision.replyContent() == null || decision.replyContent().isBlank()
                ? null
                : (validReplyDecision(decision) ? decision : null);
            case NO_REPLY -> validNoReplyDecision(decision) ? decision : null;
            case SWITCH_OWNER -> validSwitchOwnerDecision(decision)
                && canSwitchOwner(owner, decision.targetAgentId(), activePlaybookRunId, switchCount)
                    ? decision
                    : null;
            case RUN_PLAYBOOK -> validRunPlaybookDecision(decision)
                && canRunPlaybook(owner, decision.playbookId(), decision.playbookInput(), activePlaybookRunId)
                    ? decision
                    : null;
            case SESSION_HUMAN_HANDOFF -> validSessionHandoffDecision(decision) ? decision : null;
        };
    }

    private boolean canSwitchOwner(AgentConfig owner, String targetAgentId, String activePlaybookRunId, int switchCount) {
        if (targetAgentId == null || targetAgentId.isBlank()) {
            return false;
        }
        if (activePlaybookRunId != null || snapshot.sessionHumanHandoffActive()) {
            return false;
        }
        if (switchCount >= startRequest.assistant().ownerPolicy().maxOwnerSwitchesPerTurn()) {
            return false;
        }
        if (!owner.switchableOwnerAgentIds().contains(targetAgentId)) {
            return false;
        }
        AgentConfig targetAgent = agentsById.get(targetAgentId);
        return targetAgent != null && targetAgent.canOwnSession();
    }

    private boolean canRunPlaybook(
        AgentConfig owner,
        String playbookId,
        Map<String, Object> playbookInput,
        String activePlaybookRunId
    ) {
        if (playbookId == null || playbookId.isBlank()) {
            return false;
        }
        if (activePlaybookRunId != null || snapshot.sessionHumanHandoffActive()) {
            return false;
        }
        if (!owner.playbookIds().contains(playbookId)) {
            return false;
        }
        PlaybookConfig playbook = playbooksById.get(playbookId);
        if (playbook == null) {
            return false;
        }
        try {
            jsonSchemaValidator.validate(playbook.inputSchema(), playbookInput == null ? Map.of() : playbookInput, "playbookInput");
            return true;
        } catch (IllegalStateException error) {
            return false;
        }
    }

    private boolean validReplyDecision(AgentDecision decision) {
        return decision.replyContent() != null && !decision.replyContent().isBlank();
    }

    private boolean validNoReplyDecision(AgentDecision decision) {
        return true;
    }

    private boolean validSwitchOwnerDecision(AgentDecision decision) {
        return decision.targetAgentId() != null && !decision.targetAgentId().isBlank();
    }

    private boolean validRunPlaybookDecision(AgentDecision decision) {
        return decision.playbookId() != null
            && !decision.playbookId().isBlank()
            && decision.playbookInput() != null;
    }

    private boolean validSessionHandoffDecision(AgentDecision decision) {
        return true;
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
        return eventId;
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
        boolean shouldDrain = snapshot.draining() || guardrailExceeded();
        if (!shouldDrain) {
            return;
        }
        if (!snapshot.draining()) {
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
        }
        if (!hasNonIdleExecution()) {
            ended = true;
        }
    }

    private boolean guardrailExceeded() {
        Duration maxWorkflowAge = startRequest.assistant().sessionPolicy().maxWorkflowAge();
        int maxWorkflowHistoryEvents = startRequest.assistant().sessionPolicy().maxWorkflowHistoryEvents();
        boolean ageExceeded = maxWorkflowAge != null
            && !maxWorkflowAge.isZero()
            && !maxWorkflowAge.isNegative()
            && Workflow.currentTimeMillis() - workflowStartedAt.toEpochMilli() >= maxWorkflowAge.toMillis();
        boolean historyExceeded = maxWorkflowHistoryEvents > 0 && events.size() >= maxWorkflowHistoryEvents;
        return ageExceeded || historyExceeded;
    }

    private boolean shouldEndIdleSession() {
        return !hasNonIdleExecution()
            && snapshot.idleDeadline() != null
            && !snapshot.draining()
            && Workflow.currentTimeMillis() >= snapshot.idleDeadline().toEpochMilli();
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
