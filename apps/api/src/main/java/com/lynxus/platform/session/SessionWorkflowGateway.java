package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.EndHumanHandoffSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.contracts.session.SessionWorkflow;
import com.lynxus.platform.shared.ConflictException;
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

    void submitUserMessage(String workflowId, UserMessage message);

    SessionSnapshot currentSnapshot(String workflowId);

    boolean isWorkflowOpen(String workflowId);

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
            @Value("${lynxus.temporal.task-queue}") String taskQueue
        ) {
            this.workflowClient = workflowClient;
            this.taskQueue = taskQueue;
        }

        @Override
        public void start(SessionStartRequest request) {
            SessionWorkflow workflow = newStartWorkflowStub(request.sessionId());
            WorkflowClient.start(workflow::run, request);
        }

        @Override
        public void submitUserMessage(String workflowId, UserMessage message) {
            WorkflowStub workflowStub = existingUntypedWorkflowStub(workflowId);
            try {
                workflowStub.startUpdate(
                    UpdateOptions.<SessionUserMessageUpdateResult>newBuilder()
                        .setUpdateName("submitUserMessage")
                        .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                        .setResultClass(SessionUserMessageUpdateResult.class)
                        .build(),
                    message
                );
            } catch (WorkflowUpdateTimeoutOrCancelledException error) {
                throw new ConflictException("session message acceptance timed out");
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
