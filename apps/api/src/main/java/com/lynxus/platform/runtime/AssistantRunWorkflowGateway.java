package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanAction;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface AssistantRunWorkflowGateway {
    void start(WorkflowStartRequest request);

    void submitHumanAction(String workflowId, HumanAction action);

    WorkflowResult currentResult(String workflowId);

    @Component
    class TemporalAssistantRunWorkflowGateway implements AssistantRunWorkflowGateway {
        private final WorkflowClient workflowClient;
        private final String taskQueue;

        public TemporalAssistantRunWorkflowGateway(
            WorkflowClient workflowClient,
            @Value("${lynxus.temporal.task-queue}") String taskQueue
        ) {
            this.workflowClient = workflowClient;
            this.taskQueue = taskQueue;
        }

        @Override
        public void start(WorkflowStartRequest request) {
            AssistantRunWorkflow workflow = newStartWorkflowStub(request.workflowInstanceId());
            WorkflowClient.start(workflow::run, request);
        }

        @Override
        public void submitHumanAction(String workflowId, HumanAction action) {
            AssistantRunWorkflow workflow = existingWorkflowStub(workflowId);
            workflow.submitHumanAction(action);
        }

        @Override
        public WorkflowResult currentResult(String workflowId) {
            try {
                return existingWorkflowStub(workflowId).currentResult();
            } catch (RuntimeException error) {
                return null;
            }
        }

        private AssistantRunWorkflow newStartWorkflowStub(String workflowId) {
            return workflowClient.newWorkflowStub(
                AssistantRunWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(workflowId)
                    .build()
            );
        }

        private AssistantRunWorkflow existingWorkflowStub(String workflowId) {
            return workflowClient.newWorkflowStub(AssistantRunWorkflow.class, workflowId);
        }
    }
}
