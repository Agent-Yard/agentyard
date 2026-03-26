package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeImportWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
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
        currentResult = activities.runImport(request);
        return currentResult;
    }

    @Override
    public KnowledgeJobResult currentResult() {
        return currentResult;
    }
}
