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
    WorkflowResult startAndAwaitFirstResult(WorkflowStartRequest request);

    WorkflowResult submitHumanActionAndAwaitResult(String workflowId, HumanAction action);

    @Component
    class TemporalAssistantRunWorkflowGateway implements AssistantRunWorkflowGateway {
        private static final long INITIAL_RESULT_TIMEOUT_MILLIS = 30_000;
        private static final long RESUME_RESULT_TIMEOUT_MILLIS = 30_000;
        private static final long POLL_INTERVAL_MILLIS = 100;

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
        public WorkflowResult startAndAwaitFirstResult(WorkflowStartRequest request) {
            AssistantRunWorkflow workflow = newStartWorkflowStub(request.workflowInstanceId());
            WorkflowClient.start(workflow::run, request);
            return pollForResult(workflow, INITIAL_RESULT_TIMEOUT_MILLIS, true);
        }

        @Override
        public WorkflowResult submitHumanActionAndAwaitResult(String workflowId, HumanAction action) {
            AssistantRunWorkflow workflow = existingWorkflowStub(workflowId);
            workflow.submitHumanAction(action);
            return pollForResult(workflow, RESUME_RESULT_TIMEOUT_MILLIS, false);
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

        private WorkflowResult pollForResult(AssistantRunWorkflow workflow, long timeoutMillis, boolean allowWaitingHuman) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            WorkflowResult latest = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    latest = workflow.currentResult();
                } catch (RuntimeException error) {
                    latest = null;
                }
                if (latest != null) {
                    if (allowWaitingHuman || latest.status() != com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus.WAITING_HUMAN) {
                        return latest;
                    }
                }
                sleepQuietly();
            }
            if (latest != null) {
                return latest;
            }
            throw new IllegalStateException("workflow did not expose a result before timeout");
        }

        private void sleepQuietly() {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling workflow result", error);
            }
        }
    }
}
