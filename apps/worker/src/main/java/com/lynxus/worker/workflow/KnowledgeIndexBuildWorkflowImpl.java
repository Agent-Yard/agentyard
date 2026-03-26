package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeIndexBuildWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class KnowledgeIndexBuildWorkflowImpl implements KnowledgeIndexBuildWorkflow {
    private final KnowledgeActivities activities;
    private KnowledgeJobResult currentResult;

    public KnowledgeIndexBuildWorkflowImpl(Duration activityStartToCloseTimeout) {
        this.activities = Workflow.newActivityStub(
            KnowledgeActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activityStartToCloseTimeout)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build()
        );
    }

    @Override
    public KnowledgeJobResult run(KnowledgeIndexBuildRequest request) {
        currentResult = activities.buildIndex(request);
        return currentResult;
    }

    @Override
    public KnowledgeJobResult currentResult() {
        return currentResult;
    }
}
