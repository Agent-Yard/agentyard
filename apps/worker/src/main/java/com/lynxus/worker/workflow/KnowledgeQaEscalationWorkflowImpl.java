package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
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

public class KnowledgeQaEscalationWorkflowImpl implements KnowledgeQaEscalationWorkflow {
    private static final Duration DEFAULT_ACTIVITY_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);
    private final KnowledgeQaActivities activities;
    private WorkflowStartRequest startRequest;
    private WorkflowResult currentResult;
    private HumanAction pendingHumanAction;

    public KnowledgeQaEscalationWorkflowImpl() {
        this(DEFAULT_ACTIVITY_START_TO_CLOSE_TIMEOUT);
    }

    public KnowledgeQaEscalationWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.activities = Workflow.newActivityStub(
            KnowledgeQaActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build()
        );
    }

    @Override
    public WorkflowResult run(WorkflowStartRequest request) {
        this.startRequest = request;
        try {
            this.currentResult = activities.startExecution(request);
            while (currentResult != null && currentResult.status() == WorkflowStatus.WAITING_HUMAN) {
                Workflow.await(() -> pendingHumanAction != null);
                HumanAction action = pendingHumanAction;
                pendingHumanAction = null;
                this.currentResult = activities.resumeExecution(new WorkflowResumeRequest(
                    startRequest.taskId(),
                    startRequest.workflowInstanceId(),
                    startRequest.scenarioId(),
                    action,
                    startRequest.sessionContext(),
                    startRequest.assistant(),
                    currentResult.checkpoint()
                ));
            }
        } catch (RuntimeException error) {
            this.currentResult = failureResult(request.workflowInstanceId(), error);
        }
        return currentResult;
    }

    @Override
    public void submitHumanAction(HumanAction action) {
        this.pendingHumanAction = action;
    }

    @Override
    public WorkflowResult currentResult() {
        return currentResult;
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
