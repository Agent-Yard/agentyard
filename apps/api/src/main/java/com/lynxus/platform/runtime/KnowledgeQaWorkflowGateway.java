package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface KnowledgeQaWorkflowGateway {
    WorkflowResult execute(WorkflowStartRequest request);

    @Component
    class TemporalKnowledgeQaWorkflowGateway implements KnowledgeQaWorkflowGateway {
        private final WorkflowClient workflowClient;
        private final String taskQueue;

        public TemporalKnowledgeQaWorkflowGateway(
            WorkflowClient workflowClient,
            @Value("${lynxus.temporal.task-queue}") String taskQueue
        ) {
            this.workflowClient = workflowClient;
            this.taskQueue = taskQueue;
        }

        @Override
        public WorkflowResult execute(WorkflowStartRequest request) {
            KnowledgeQaEscalationWorkflow workflow = workflowClient.newWorkflowStub(
                KnowledgeQaEscalationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(request.workflowInstanceId())
                    .build()
            );
            return workflow.run(request);
        }
    }
}
