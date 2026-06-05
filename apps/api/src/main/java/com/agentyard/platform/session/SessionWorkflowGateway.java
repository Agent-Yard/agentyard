package com.agentyard.platform.session;

import com.agentyard.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.agentyard.contracts.session.SessionContracts.EndHumanHandoffSignal;
import com.agentyard.contracts.session.SessionContracts.HumanResumeSignal;
import com.agentyard.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.agentyard.contracts.session.SessionContracts.SessionSnapshot;
import com.agentyard.contracts.session.SessionContracts.SessionStartRequest;
import com.agentyard.contracts.session.SessionContracts.UserTurn;
import com.agentyard.contracts.session.SessionContracts.UserTurnAcceptedResult;
import com.agentyard.contracts.session.SessionWorkflow;
import com.agentyard.platform.shared.ConflictException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.WorkflowUpdateTimeoutOrCancelledException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface SessionWorkflowGateway {
    void start(SessionStartRequest request);

    void submitUserTurn(String workflowId, String updateId, UserTurn turn);

    SessionSnapshot currentSnapshot(String workflowId);

    boolean isWorkflowOpen(String workflowId);

    boolean isWorkflowClosed(String workflowId);

    void humanResume(String workflowId, HumanResumeSignal signal);

    void externalCallback(String workflowId, ExternalCallbackSignal signal);

    void endHumanHandoff(String workflowId, EndHumanHandoffSignal signal);

    void humanOperatorReply(String workflowId, HumanOperatorReplySignal signal);

    @Component
    class TemporalSessionWorkflowGateway implements SessionWorkflowGateway {
        private final WorkflowClient workflowClient;
        private final String taskQueue;

        public TemporalSessionWorkflowGateway(
            WorkflowClient workflowClient,
            @Value("${agentyard.temporal.task-queue}") String taskQueue
        ) {
            this.workflowClient = workflowClient;
            this.taskQueue = taskQueue;
        }

        @Override
        public void start(SessionStartRequest request) {
            SessionWorkflow workflow = newStartWorkflowStub(request.sessionId());
            try {
                WorkflowClient.start(workflow::run, request);
            } catch (WorkflowExecutionAlreadyStarted ignored) {
                // Ensuring an already-started workflow is an idempotent success for send-turn recovery.
            }
        }

        @Override
        public void submitUserTurn(String workflowId, String updateId, UserTurn turn) {
            WorkflowStub workflowStub = existingUntypedWorkflowStub(workflowId);
            try {
                workflowStub.startUpdate(
                    UpdateOptions.<UserTurnAcceptedResult>newBuilder()
                        .setUpdateName("submitUserTurn")
                        .setUpdateId(updateId)
                        .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                        .setResultClass(UserTurnAcceptedResult.class)
                        .build(),
                    turn
                );
            } catch (WorkflowUpdateTimeoutOrCancelledException error) {
                throw new ConflictException("session turn acceptance timed out");
            }
        }

        @Override
        public SessionSnapshot currentSnapshot(String workflowId) {
            return existingWorkflowStub(workflowId).currentSnapshot();
        }

        @Override
        public boolean isWorkflowOpen(String workflowId) {
            try {
                return WorkflowStub.fromTyped(existingWorkflowStub(workflowId)).describe().getCloseTime() == null;
            } catch (RuntimeException error) {
                return false;
            }
        }

        @Override
        public boolean isWorkflowClosed(String workflowId) {
            try {
                return WorkflowStub.fromTyped(existingWorkflowStub(workflowId)).describe().getCloseTime() != null;
            } catch (RuntimeException error) {
                return false;
            }
        }

        @Override
        public void humanResume(String workflowId, HumanResumeSignal signal) {
            existingWorkflowStub(workflowId).humanResume(signal);
        }

        @Override
        public void externalCallback(String workflowId, ExternalCallbackSignal signal) {
            existingWorkflowStub(workflowId).externalCallback(signal);
        }

        @Override
        public void endHumanHandoff(String workflowId, EndHumanHandoffSignal signal) {
            existingWorkflowStub(workflowId).endHumanHandoff(signal);
        }

        @Override
        public void humanOperatorReply(String workflowId, HumanOperatorReplySignal signal) {
            existingWorkflowStub(workflowId).humanOperatorReply(signal);
        }

        private SessionWorkflow newStartWorkflowStub(String workflowId) {
            return workflowClient.newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(workflowId)
                    .build()
            );
        }

        private SessionWorkflow existingWorkflowStub(String workflowId) {
            return workflowClient.newWorkflowStub(SessionWorkflow.class, workflowId);
        }

        private WorkflowStub existingUntypedWorkflowStub(String workflowId) {
            return workflowClient.newUntypedWorkflowStub(workflowId);
        }
    }
}
