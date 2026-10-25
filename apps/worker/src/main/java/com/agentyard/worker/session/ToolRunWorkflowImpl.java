package com.agentyard.worker.session;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.agent.ToolRunWorkflow;
import com.agentyard.contracts.runtime.RuntimeContracts.*;
import com.agentyard.worker.agent.ToolRunActivities;
import com.agentyard.worker.runtime.ExecutionPaused;
import java.util.function.Supplier;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** A durable tool invocation with its own waits; it never owns or blocks an Agent Session. */
public class ToolRunWorkflowImpl implements ToolRunWorkflow {
    private final Duration activityTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaValidator validator = new JsonSchemaValidator(mapper);
    private final Set<String> consumedSignals = new HashSet<>();
    private ToolRunRuntime runtime;
    private PlaybookDefinition definition;
    private String toolRunId;
    private Map<String, Object> state = Map.of();
    private Map<String, Object> waiting = Map.of();
    private ToolRunResume pendingResume;
    private boolean cancelled;
    private boolean paused;
    private long controlRevision;
    private CancellationScope nodeScope;
    private int visit;

    public ToolRunWorkflowImpl() { this(Duration.ofMinutes(6)); }
    public ToolRunWorkflowImpl(Duration activityTimeout) { this.activityTimeout = activityTimeout; }

    public void run(ToolRunStart start) {
        toolRunId = start.toolRunId();
        try {
            awaitRunnable();
            nodeScope=Workflow.newCancellationScope(() -> runtime = beforeEffects(() -> persistence().loadToolRun(toolRunId)));
            try { nodeScope.run(); } finally { nodeScope=null; }
            if (isTerminal(runtime.run().status())) {
                return;
            }
            definition = new PlaybookDefinition(runtime.playbook());
            state = new LinkedHashMap<>(runtime.run().input());
            validator.validate(runtime.playbook().inputSchema(), state, "playbookInput");
            progress(ToolRunStatus.RUNNING, null);
            executeNodes();
        } catch (CanceledFailure error) {
            cancelled = true;
            recordCancelled();
        } catch (RuntimeException error) {
            if (cancelled || CancellationScope.current().isCancelRequested()) {
                recordCancelled();
            } else {
                progress(ToolRunStatus.FAILED, error.getMessage() == null ? "playbook execution failed" : error.getMessage());
            }
        }
    }

    public void resume(ToolRunResume signal) {
        if (signal == null || cancelled || signal.signalId() == null || signal.signalId().isBlank()
            || consumedSignals.contains(signal.signalId()) || pendingResume != null
            || signal.waitingId() == null || !signal.waitingId().equals(waiting.get("waitingId"))
            || signal.source() == null || !signal.source().name().equals(waiting.get("source"))) {
            return;
        }
        pendingResume = signal;
    }

    public void cancel() {
        cancelled = true;
        if (nodeScope != null) {
            nodeScope.cancel();
        }
    }

    public void setPaused(boolean paused) { this.paused = paused; controlRevision++; }

    private <T> T beforeEffects(Supplier<T> operation) {
        int missedSignals=0;
        for(;;) {
            awaitRunnable();
            long observedRevision=controlRevision;
            try { return operation.get(); }
            catch(RuntimeException failure) {
                if(!ExecutionPaused.isPaused(failure)) throw failure;
                // The DB pause can precede its signal, or resume can arrive before this response.
                // Never fabricate paused=true: it could overwrite an already delivered resume.
                if(!paused && controlRevision==observedRevision) {
                    long seconds=Math.min(300,1L<<Math.min(missedSignals++,9));
                    Workflow.await(Duration.ofSeconds(seconds),()->cancelled||paused||controlRevision!=observedRevision);
                }
            }
        }
    }

    private void awaitRunnable() {
        Workflow.await(() -> cancelled || !paused);
        if (cancelled) throw new CanceledFailure("tool invocation cancelled");
    }

    private void executeNodes() {
        String nodeKey = runtime.playbook().entryNodeKey();
        while (nodeKey != null) {
            awaitRunnable();
            if (cancelled) {
                recordCancelled();
                return;
            }
            if (++visit > 10_000) {
                throw new IllegalStateException("playbook exceeded 10000 node visits; split or schedule the continuing work");
            }
            PlaybookNode node = definition.requireNode(nodeKey);
            String executionId = toolRunId + ":" + node.nodeKey() + ":" + visit;
            switch (node.nodeType()) {
                case STEP, TOOL_TASK -> {
                    PlaybookNodeActivities.PlaybookNodeExecutionResult result = executeNode(node, executionId);
                    mergeState(node.nodeKey(), result.statePatch());
                    if (result.terminalStatus() != null) {
                        if (result.terminalStatus() != PlaybookRunStatus.FAILED) {
                            throw new IllegalStateException("tool nodes may only terminate with FAILED; use END for success");
                        }
                        progress(ToolRunStatus.FAILED, result.failureReason());
                        return;
                    }
                    progress(ToolRunStatus.RUNNING, null);
                    nodeKey = definition.nextNodeKey(node.nodeKey(), result.routeKey());
                }
                case HUMAN_TASK, EXTERNAL_INTERACTION -> {
                    waitForReply(node, executionId);
                    nodeKey = definition.nextNodeKey(node.nodeKey(), null);
                }
                case END -> {
                    complete(node);
                    return;
                }
            }
        }
        throw new IllegalStateException("playbook ended without END node");
    }

    private PlaybookNodeActivities.PlaybookNodeExecutionResult executeNode(PlaybookNode node, String executionId) {
        var result = new PlaybookNodeActivities.PlaybookNodeExecutionResult[1];
        var request = new ToolRunNodeRequest(toolRunId, node.nodeKey(), executionId, state);
        nodeScope = Workflow.newCancellationScope(() -> result[0] = beforeEffects(() -> node.nodeType() == PlaybookNodeType.STEP
            ? nodes(false).executeStep(request) : nodes(true).executeTool(request)));
        try {
            nodeScope.run();
            return result[0];
        } finally {
            nodeScope = null;
        }
    }

    private void waitForReply(PlaybookNode node, String executionId) {
        boolean human = node.nodeType() == PlaybookNodeType.HUMAN_TASK;
        if (human && !runtime.playbook().allowHumanTask()
            || !human && !runtime.playbook().allowExternalInteraction()) {
            throw new IllegalStateException("playbook does not permit " + node.nodeType());
        }
        waiting = Map.of("waitingId", executionId, "nodeKey", node.nodeKey(), "type", node.nodeType().name(),
            "source", human ? PlaybookResumeSource.HUMAN.name() : PlaybookResumeSource.EXTERNAL_SYSTEM.name(),
            "description", node.description() == null ? node.nodeName() : node.description(), "state", state);
        progress(ToolRunStatus.WAITING, null);
        Workflow.await(() -> cancelled || !paused && pendingResume != null);
        if (cancelled) {
            throw new CanceledFailure("tool invocation cancelled");
        }
        ToolRunResume signal = pendingResume;
        pendingResume = null;
        consumedSignals.add(signal.signalId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("nodeKey", node.nodeKey());
        response.put("waitingId", signal.waitingId());
        response.put("signalId", signal.signalId());
        response.put("resumeSource", signal.source().name());
        response.put("participantId", signal.participantId());
        response.put("sourceMessageId", signal.sourceMessageId());
        response.put("payload", signal.payload());
        mergeState(node.nodeKey(), Map.of("playbook.lastResume", response));
        waiting = Map.of();
        progress(ToolRunStatus.RUNNING, null);
        persistence().acknowledgeToolRunSignal(toolRunId, signal.signalId());
    }

    private void complete(PlaybookNode node) {
        String status = node.config().get("status") instanceof String configured ? configured : "SUCCEEDED";
        if (!status.equals("SUCCEEDED") && !status.equals("FAILED")) {
            throw new IllegalStateException("playbook END status must be SUCCEEDED or FAILED");
        }
        Object configuredResult = node.config().get("result");
        if (configuredResult instanceof Map<?, ?> result) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            result.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            state = normalized;
        }
        if (status.equals("SUCCEEDED")) {
            validator.validate(runtime.playbook().resultSchema(), state, "playbookResult");
        }
        progress(ToolRunStatus.valueOf(status), node.config().get("failureReason") instanceof String reason ? reason : null);
    }

    private void mergeState(String nodeKey, Map<String, Object> patch) {
        LinkedHashMap<String, Object> updated = new LinkedHashMap<>(state);
        updated.putAll(patch);
        updated.put("playbook.lastNodeKey", nodeKey);
        state = updated;
    }

    private void recordCancelled() {
        waiting = Map.of();
        Workflow.newDetachedCancellationScope(() -> progress(ToolRunStatus.CANCELLED, "tool invocation cancelled")).run();
    }

    private void progress(ToolRunStatus status, String reason) {
        persistence().recordToolRunProgress(toolRunId,
            new ToolRunProgress(status, state, status == ToolRunStatus.WAITING ? waiting : Map.of(), reason));
    }

    private ToolRunActivities persistence() {
        return Workflow.newActivityStub(ToolRunActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofMinutes(1)).build()).build());
    }

    private PlaybookNodeActivities nodes(boolean receiptProtected) {
        PlaybookExecutionPolicy policy = runtime.playbook().executionPolicy();
        Duration timeout = TemporalPolicySupport.parseTimeoutPolicy(policy == null ? null : policy.timeoutPolicy(), activityTimeout);
        RetryOptions noReplay = RetryOptions.newBuilder().setMaximumAttempts(1).build();
        // Runtime receipts return completed results or stop on unknown outcomes before a second external write.
        RetryOptions retry = receiptProtected
            ? TemporalPolicySupport.parseRetryPolicy(policy == null ? null : policy.retryPolicy(), noReplay, mapper)
            : noReplay;
        return Workflow.newActivityStub(PlaybookNodeActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(timeout)
            .setRetryOptions(retry).build());
    }

    private boolean isTerminal(ToolRunStatus status) {
        return status == ToolRunStatus.CANCELLED || status == ToolRunStatus.FAILED || status == ToolRunStatus.SUCCEEDED;
    }
}
