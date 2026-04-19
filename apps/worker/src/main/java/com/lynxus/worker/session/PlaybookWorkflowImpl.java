package com.lynxus.worker.session;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import com.lynxus.contracts.session.SessionContracts.PlaybookProgressType;
import com.lynxus.contracts.session.SessionContracts.PlaybookProgressUpdate;
import com.lynxus.contracts.session.SessionContracts.PlaybookResumeSignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookResumeSource;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.PlaybookStartRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookWaitingType;
import com.lynxus.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.lynxus.contracts.session.SessionWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public class PlaybookWorkflowImpl implements PlaybookWorkflow {
    private final Duration defaultActivityStartToCloseTimeout;
    private final ObjectMapper objectMapper;
    private PlaybookRun currentRun;
    private PlaybookResumeSignal pendingResume;
    private PlaybookDefinition definition;
    private PlaybookStartRequest startRequest;
    private SessionWorkflow parentWorkflow;
    private Map<String, Object> workingState = Map.of();

    public PlaybookWorkflowImpl() {
        this(Duration.ofMinutes(2));
    }

    public PlaybookWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.defaultActivityStartToCloseTimeout = activityStartToCloseTimeout;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public PlaybookRun run(PlaybookStartRequest request) {
        this.startRequest = request;
        this.definition = new PlaybookDefinition(request.playbook());
        this.parentWorkflow = Workflow.newExternalWorkflowStub(SessionWorkflow.class, request.sessionId());
        this.pendingResume = null;
        this.workingState = new LinkedHashMap<>(request.input());
        this.currentRun = copyRun(PlaybookRunStatus.RUNNING, null, Map.of(), null);
        try {
            return executeNodes();
        } catch (RuntimeException error) {
            this.currentRun = copyRun(
                PlaybookRunStatus.FAILED,
                null,
                currentRun == null ? Map.of() : currentRun.result(),
                error.getMessage() == null ? "playbook workflow failed" : error.getMessage()
            );
            return currentRun;
        }
    }

    @Override
    public void resume(PlaybookResumeSignal signal) {
        if (currentRun == null || signal == null) {
            return;
        }
        if (!currentRun.runId().equals(signal.playbookRunId()) || currentRun.status() != PlaybookRunStatus.WAITING) {
            return;
        }
        if (expectedResumeSource() != signal.source()) {
            return;
        }
        this.pendingResume = signal;
    }

    @Override
    public PlaybookRun currentRun() {
        return currentRun;
    }

    private PlaybookRun executeNodes() {
        String currentNodeKey = definition.entryNode().nodeKey();
        while (currentNodeKey != null) {
            PlaybookNode node = definition.requireNode(currentNodeKey);
            switch (node.nodeType()) {
                case STEP -> currentNodeKey = executeStepNode(node);
                case TOOL_TASK -> currentNodeKey = executeToolNode(node);
                case HUMAN_TASK -> currentNodeKey = waitForResume(node, PlaybookWaitingType.HUMAN_TASK);
                case EXTERNAL_INTERACTION -> currentNodeKey = waitForResume(node, PlaybookWaitingType.EXTERNAL_INTERACTION);
                case END -> {
                    return completeAtEndNode(node);
                }
            }
        }
        throw new IllegalStateException("playbook ended without END node");
    }

    private String executeStepNode(PlaybookNode node) {
        PlaybookNodeActivities.PlaybookNodeExecutionResult result = nodeActivities().executeStep(nodeRequest(node));
        return consumeNodeExecutionResult(node, result);
    }

    private String executeToolNode(PlaybookNode node) {
        PlaybookNodeActivities.PlaybookNodeExecutionResult result = nodeActivities().executeTool(nodeRequest(node));
        return consumeNodeExecutionResult(node, result);
    }

    private String consumeNodeExecutionResult(
        PlaybookNode node,
        PlaybookNodeActivities.PlaybookNodeExecutionResult result
    ) {
        mergeStatePatch(node, result.statePatch());
        if (result.terminalStatus() == PlaybookRunStatus.FAILED || result.terminalStatus() == PlaybookRunStatus.CANCELLED) {
            this.currentRun = copyRun(result.terminalStatus(), null, currentRun.result(), result.failureReason());
            return null;
        }
        return definition.nextNodeKey(node.nodeKey(), result.routeKey());
    }

    private String waitForResume(PlaybookNode node, PlaybookWaitingType waitingType) {
        String waitingReason = waitingReason(waitingType, node.nodeKey());
        this.currentRun = copyRun(PlaybookRunStatus.WAITING, waitingReason, currentRun.result(), null);
        parentWorkflow.syncPlaybookProgress(
            new PlaybookProgressUpdate(
                PlaybookProgressType.WAITING,
                currentRun,
                node.nodeKey(),
                waitingType,
                null,
                Map.of()
            )
        );
        Workflow.await(() -> pendingResume != null);
        PlaybookResumeSignal resume = pendingResume;
        pendingResume = null;
        mergeStatePatch(
            node,
            Map.of(
                "playbook.lastResume",
                Map.of(
                    "nodeKey", node.nodeKey(),
                    "resumeSource", resume.source().name(),
                    "payload", resume.payload()
                )
            )
        );
        this.currentRun = copyRun(PlaybookRunStatus.RUNNING, null, currentRun.result(), null);
        parentWorkflow.syncPlaybookProgress(
            new PlaybookProgressUpdate(
                PlaybookProgressType.RESUMED,
                currentRun,
                node.nodeKey(),
                waitingType,
                resume.source(),
                resume.payload()
            )
        );
        return definition.nextNodeKey(node.nodeKey(), null);
    }

    private PlaybookRun completeAtEndNode(PlaybookNode node) {
        Map<String, Object> configuredResult = mapConfig(node.config().get("result"));
        String completionStatus = stringConfig(node.config().get("status"));
        PlaybookRunStatus status = completionStatus == null ? PlaybookRunStatus.SUCCEEDED : PlaybookRunStatus.valueOf(completionStatus);
        String failureReason = stringConfig(node.config().get("failureReason"));
        Map<String, Object> result = configuredResult.isEmpty() ? Map.copyOf(workingState) : configuredResult;
        this.currentRun = copyRun(
            status,
            null,
            status == PlaybookRunStatus.SUCCEEDED ? result : Map.of(),
            status == PlaybookRunStatus.FAILED ? defaultFailureReason(failureReason) : failureReason
        );
        return currentRun;
    }

    private PlaybookNodeActivities.PlaybookNodeExecutionRequest nodeRequest(PlaybookNode node) {
        return new PlaybookNodeActivities.PlaybookNodeExecutionRequest(
            startRequest.sessionId(),
            startRequest.playbookRunId(),
            startRequest.playbook().playbookId(),
            node.nodeKey(),
            node.nodeName(),
            startRequest.ownerAgent(),
            node.scriptRef(),
            node.scriptVersion(),
            node.toolId(),
            node.toolOperation(),
            Map.copyOf(workingState),
            node.config()
        );
    }

    private void mergeStatePatch(PlaybookNode node, Map<String, Object> statePatch) {
        if (statePatch == null || statePatch.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> nextState = new LinkedHashMap<>(workingState);
        nextState.putAll(statePatch);
        nextState.put("playbook.lastNodeKey", node.nodeKey());
        this.workingState = nextState;
    }

    private PlaybookResumeSource expectedResumeSource() {
        if (currentRun == null || currentRun.waitingReason() == null) {
            return null;
        }
        if (currentRun.waitingReason().startsWith("human_task:")) {
            return PlaybookResumeSource.HUMAN;
        }
        if (currentRun.waitingReason().startsWith("external_interaction:")) {
            return PlaybookResumeSource.EXTERNAL_SYSTEM;
        }
        return null;
    }

    private String waitingReason(PlaybookWaitingType waitingType, String nodeKey) {
        return switch (waitingType) {
            case HUMAN_TASK -> "human_task:" + nodeKey;
            case EXTERNAL_INTERACTION -> "external_interaction:" + nodeKey;
        };
    }

    private PlaybookRun copyRun(
        PlaybookRunStatus status,
        String waitingReason,
        Map<String, Object> result,
        String failureReason
    ) {
        Instant now = Instant.ofEpochMilli(Workflow.currentTimeMillis());
        return new PlaybookRun(
            startRequest.playbookRunId(),
            startRequest.sessionId(),
            startRequest.triggeringEventId(),
            startRequest.playbook().playbookId(),
            startRequest.ownerAgentId(),
            status,
            startRequest.input(),
            result == null ? Map.of() : result,
            failureReason,
            currentRun == null ? now : currentRun.createdAt(),
            now,
            waitingReason
        );
    }

    private PlaybookNodeActivities nodeActivities() {
        return Workflow.newActivityStub(PlaybookNodeActivities.class, activityOptions());
    }

    private ActivityOptions activityOptions() {
        PlaybookExecutionPolicy executionPolicy = startRequest.playbook().executionPolicy();
        Duration timeout = TemporalPolicySupport.parseTimeoutPolicy(
            executionPolicy == null ? null : executionPolicy.timeoutPolicy(),
            defaultActivityStartToCloseTimeout
        );
        RetryOptions retryOptions = TemporalPolicySupport.parseRetryPolicy(
            executionPolicy == null ? null : executionPolicy.retryPolicy(),
            RetryOptions.newBuilder().setMaximumAttempts(1).build(),
            objectMapper
        );
        return ActivityOptions.newBuilder()
            .setStartToCloseTimeout(timeout)
            .setRetryOptions(retryOptions)
            .build();
    }

    private Map<String, Object> mapConfig(Object value) {
        if (!(value instanceof Map<?, ?> configMap) || configMap.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        configMap.forEach((key, item) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, item);
            }
        });
        return result;
    }

    private String stringConfig(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private String defaultFailureReason(String failureReason) {
        return failureReason == null ? "playbook failed" : failureReason;
    }
}
