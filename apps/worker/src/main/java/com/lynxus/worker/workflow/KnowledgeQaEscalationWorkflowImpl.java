package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class KnowledgeQaEscalationWorkflowImpl implements KnowledgeQaEscalationWorkflow {
    private final KnowledgeQaActivities activities = Workflow.newActivityStub(
        KnowledgeQaActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
    );

    @Override
    public WorkflowResult run(WorkflowStartRequest request) {
        return activities.executeAgentRuntime(request);
    }
}
