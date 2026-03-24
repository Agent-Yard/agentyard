package com.lynxus.platform.runtime;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanAction;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface KnowledgeQaWorkflowGateway {
    WorkflowResult startAndAwaitFirstResult(WorkflowStartRequest request);

    WorkflowResult submitHumanActionAndAwaitResult(String workflowId, HumanAction action);

    @Component
    class TemporalKnowledgeQaWorkflowGateway implements KnowledgeQaWorkflowGateway {
        private static final long INITIAL_RESULT_TIMEOUT_MILLIS = 30_000;
        private static final long RESUME_RESULT_TIMEOUT_MILLIS = 30_000;
        private static final long POLL_INTERVAL_MILLIS = 100;

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
        public WorkflowResult startAndAwaitFirstResult(WorkflowStartRequest request) {
            KnowledgeQaEscalationWorkflow workflow = workflowStub(request.workflowInstanceId());
            WorkflowClient.start(workflow::run, request);
            return pollForResult(workflow, INITIAL_RESULT_TIMEOUT_MILLIS, true);
        }

        @Override
        public WorkflowResult submitHumanActionAndAwaitResult(String workflowId, HumanAction action) {
            KnowledgeQaEscalationWorkflow workflow = workflowStub(workflowId);
            workflow.submitHumanAction(action);
            return pollForResult(workflow, RESUME_RESULT_TIMEOUT_MILLIS, false);
        }

        private KnowledgeQaEscalationWorkflow workflowStub(String workflowId) {
            return workflowClient.newWorkflowStub(
                KnowledgeQaEscalationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(workflowId)
                    .build()
            );
        }

        private WorkflowResult pollForResult(KnowledgeQaEscalationWorkflow workflow, long timeoutMillis, boolean allowWaitingHuman) {
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
