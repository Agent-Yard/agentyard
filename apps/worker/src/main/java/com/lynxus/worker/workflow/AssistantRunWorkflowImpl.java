package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeAction;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureCategory;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import com.lynxus.worker.logging.WorkerLogContext;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;

public class AssistantRunWorkflowImpl implements AssistantRunWorkflow {
    private static final Duration DEFAULT_ACTIVITY_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);
    private static final Logger LOGGER = Workflow.getLogger(AssistantRunWorkflowImpl.class);
    private final AssistantRunActivities activities;
    private WorkflowStartRequest startRequest;
    private WorkflowResult currentResult;
    private ResumeAction pendingResumeAction;

    public AssistantRunWorkflowImpl() {
        this(DEFAULT_ACTIVITY_START_TO_CLOSE_TIMEOUT);
    }

    public AssistantRunWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.activities = Workflow.newActivityStub(
            AssistantRunActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build()
        );
    }

    @Override
    public WorkflowResult run(WorkflowStartRequest request) {
        try (WorkerLogContext.Scope ignored = WorkerLogContext.open(request.logContext())) {
            this.startRequest = request;
            LOGGER.info("workflow {} started for task {}", request.workflowInstanceId(), request.taskId());
            this.currentResult = runningResult(
                request.workflowInstanceId(),
                "workflow-starting",
                "流程启动",
                "流程已启动，正在执行首轮节点。"
            );
            try {
                this.currentResult = activities.startExecution(request);
                LOGGER.info(
                    "workflow {} initial result status={} currentNode={} summary={}",
                    request.workflowInstanceId(),
                    currentResult.status(),
                    currentResult.currentNodeKey(),
                    currentResult.summary()
                );
                while (currentResult != null && currentResult.status() == WorkflowStatus.WAITING_RESUME) {
                    WorkflowResult waitingResult = currentResult;
                    LOGGER.info(
                        "workflow {} entered WAITING_RESUME waitingNode={} resumeNode={}",
                        request.workflowInstanceId(),
                        waitingResult.resumeTask() == null ? null : waitingResult.resumeTask().nodeKey(),
                        waitingResult.checkpoint() == null ? null : waitingResult.checkpoint().currentNodeKey()
                    );
                    Workflow.await(() -> pendingResumeAction != null);
                    ResumeAction action = pendingResumeAction;
                    pendingResumeAction = null;
                    LOGGER.info(
                        "workflow {} received resume action type={} source={}",
                        request.workflowInstanceId(),
                        action.type(),
                        action.source()
                    );
                    this.currentResult = runningResult(
                        startRequest.workflowInstanceId(),
                        waitingResult.currentNodeKey(),
                        "workflow-resuming",
                        "已收到恢复动作，流程继续执行中。"
                    );
                    this.currentResult = activities.resumeExecution(new WorkflowResumeRequest(
                        startRequest.taskId(),
                        startRequest.workflowInstanceId(),
                        startRequest.scenarioId(),
                        action,
                        startRequest.sessionContext(),
                        startRequest.assistant(),
                        waitingResult.checkpoint(),
                        startRequest.logContext()
                    ));
                    LOGGER.info(
                        "workflow {} resumed result status={} currentNode={} summary={}",
                        request.workflowInstanceId(),
                        currentResult.status(),
                        currentResult.currentNodeKey(),
                        currentResult.summary()
                    );
                }
            } catch (RuntimeException error) {
                LOGGER.error("workflow {} failed", request.workflowInstanceId(), error);
                this.currentResult = failureResult(request.workflowInstanceId(), error);
            }
            LOGGER.info(
                "workflow {} finished with status={} currentNode={} summary={}",
                request.workflowInstanceId(),
                currentResult == null ? null : currentResult.status(),
                currentResult == null ? null : currentResult.currentNodeKey(),
                currentResult == null ? null : currentResult.summary()
            );
            return currentResult;
        }
    }

    @Override
    public void submitResumeAction(ResumeAction action) {
        LOGGER.info(
            "workflow {} signal submitResumeAction type={} source={} userId={}",
            startRequest == null ? null : startRequest.workflowInstanceId(),
            action == null ? null : action.type(),
            action == null ? null : action.source(),
            action == null ? null : action.userId()
        );
        this.pendingResumeAction = action;
    }

    @Override
    public WorkflowResult currentResult() {
        return currentResult;
    }

    private WorkflowResult runningResult(
        String workflowInstanceId,
        String currentNodeKey,
        String nodeKey,
        String summary
    ) {
        return new WorkflowResult(
            workflowInstanceId,
            WorkflowStatus.RUNNING,
            summary,
            null,
            currentNodeKey,
            null,
            null,
            null,
            null,
            List.of(new NodeSnapshot(nodeKey, "流程运行中", NodeStatus.RUNNING, summary, workflowNow())),
            List.of(),
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            SharedSessionState.empty(),
            WorkflowContracts.AgentTurnState.empty()
        );
    }

    private WorkflowResult failureResult(String workflowInstanceId, RuntimeException error) {
        String message = rootCauseMessage(error);
        WorkflowFailureSnapshot failure = workflowFailure(error);
        return new WorkflowResult(
            workflowInstanceId,
            WorkflowStatus.FAILED,
            "流程执行失败：" + message,
            null,
            null,
            null,
            null,
            null,
            failure,
            List.of(new NodeSnapshot("workflow-failed", "流程失败", NodeStatus.FAILED, message, workflowNow())),
            List.of(),
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            SharedSessionState.empty(),
            WorkflowContracts.AgentTurnState.empty()
        );
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
            ? error.getMessage()
            : current.getMessage();
    }

    private WorkflowFailureSnapshot workflowFailure(Throwable error) {
        if (error instanceof ActivityFailure activityFailure) {
            Throwable cause = activityFailure.getCause();
            if (cause instanceof TimeoutFailure timeoutFailure) {
                return new WorkflowFailureSnapshot(
                    WorkflowFailureCategory.TIMEOUT,
                    "WORKFLOW_ACTIVITY_TIMEOUT",
                    firstNonBlank(timeoutFailure.getMessage(), "activity timeout"),
                    "workflow activity timed out: " + activityFailure.getActivityType(),
                    null,
                    null,
                    null,
                    null,
                    workflowNow()
                );
            }
            return new WorkflowFailureSnapshot(
                WorkflowFailureCategory.RUNTIME_FAILURE,
                "WORKFLOW_ACTIVITY_FAILURE",
                rootCauseMessage(activityFailure),
                "workflow activity failed: " + activityFailure.getActivityType(),
                null,
                null,
                null,
                null,
                workflowNow()
            );
        }
        if (error instanceof TimeoutFailure timeoutFailure) {
            return new WorkflowFailureSnapshot(
                WorkflowFailureCategory.TIMEOUT,
                "WORKFLOW_ACTIVITY_TIMEOUT",
                firstNonBlank(timeoutFailure.getMessage(), "timeout"),
                "workflow activity timed out",
                null,
                null,
                null,
                null,
                workflowNow()
            );
        }
        if (error instanceof ApplicationFailure applicationFailure) {
            return new WorkflowFailureSnapshot(
                WorkflowFailureCategory.RUNTIME_FAILURE,
                "WORKFLOW_RUNTIME_FAILURE",
                firstNonBlank(applicationFailure.getOriginalMessage(), applicationFailure.getMessage()),
                "workflow runtime failed",
                null,
                null,
                null,
                null,
                workflowNow()
            );
        }
        return new WorkflowFailureSnapshot(
            WorkflowFailureCategory.RUNTIME_FAILURE,
            "WORKFLOW_RUNTIME_FAILURE",
            rootCauseMessage(error),
            "workflow runtime failed",
            null,
            null,
            null,
            null,
            workflowNow()
        );
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null || fallback.isBlank() ? "unknown" : fallback;
    }

    private Instant workflowNow() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
}
