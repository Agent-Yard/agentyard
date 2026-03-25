package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanAction;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
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
    private HumanAction pendingHumanAction;

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
            while (currentResult != null && currentResult.status() == WorkflowStatus.WAITING_HUMAN) {
                WorkflowResult waitingResult = currentResult;
                LOGGER.info(
                    "workflow {} entered WAITING_HUMAN waitingNode={} resumeNode={}",
                    request.workflowInstanceId(),
                    waitingResult.humanTask() == null ? null : waitingResult.humanTask().nodeKey(),
                    waitingResult.checkpoint() == null ? null : waitingResult.checkpoint().currentNodeKey()
                );
                Workflow.await(() -> pendingHumanAction != null);
                HumanAction action = pendingHumanAction;
                pendingHumanAction = null;
                LOGGER.info("workflow {} received human action {}", request.workflowInstanceId(), action.action());
                this.currentResult = runningResult(
                    startRequest.workflowInstanceId(),
                    waitingResult.currentNodeKey(),
                    "workflow-resuming",
                    "已收到人工动作，流程继续执行中。"
                );
                this.currentResult = activities.resumeExecution(new WorkflowResumeRequest(
                    startRequest.taskId(),
                    startRequest.workflowInstanceId(),
                    startRequest.scenarioId(),
                    action,
                    startRequest.sessionContext(),
                    startRequest.assistant(),
                    waitingResult.checkpoint()
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

    @Override
    public void submitHumanAction(HumanAction action) {
        LOGGER.info(
            "workflow {} signal submitHumanAction action={} operator={}",
            startRequest == null ? null : startRequest.workflowInstanceId(),
            action == null ? null : action.action(),
            action == null ? null : action.operatorId()
        );
        this.pendingHumanAction = action;
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
            List.of(new NodeSnapshot(nodeKey, "流程运行中", NodeStatus.RUNNING, summary, Instant.now())),
            List.of(),
            false,
            null
        );
    }

    private WorkflowResult failureResult(String workflowInstanceId, RuntimeException error) {
        String message = rootCauseMessage(error);
        return new WorkflowResult(
            workflowInstanceId,
            WorkflowStatus.FAILED,
            "流程执行失败：" + message,
            null,
            null,
            null,
            null,
            List.of(new NodeSnapshot("workflow-failed", "流程失败", NodeStatus.FAILED, message, Instant.now())),
            List.of(),
            false,
            null
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
}
