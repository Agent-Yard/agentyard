package com.agentyard.worker.workflow;

import com.agentyard.contracts.runtime.KnowledgeImportWorkflow;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import com.agentyard.worker.logging.WorkerLogContext;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class KnowledgeImportWorkflowImpl implements KnowledgeImportWorkflow {
    private final KnowledgeActivities activities;
    private KnowledgeJobResult currentResult;

    public KnowledgeImportWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.activities = Workflow.newActivityStub(
            KnowledgeActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build()
        );
    }

    @Override
    public KnowledgeJobResult run(KnowledgeImportRequest request) {
        try (WorkerLogContext.Scope _ = WorkerLogContext.open(request.logContext())) {
            currentResult = activities.runImport(request);
            return currentResult;
        }
    }

    @Override
    public KnowledgeJobResult currentResult() {
        return currentResult;
    }
}
