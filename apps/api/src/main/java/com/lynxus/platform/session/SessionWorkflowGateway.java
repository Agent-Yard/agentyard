package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.contracts.session.SessionWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface SessionWorkflowGateway {
    void start(SessionStartRequest request);

    SessionUserMessageUpdateResult submitUserMessage(String workflowId, UserMessage message);

    SessionSnapshot currentSnapshot(String workflowId);

    boolean isWorkflowOpen(String workflowId);

    void humanResume(String workflowId, HumanResumeSignal signal);

    void externalCallback(String workflowId, ExternalCallbackSignal signal);

    void endHumanHandoff(String workflowId);

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
        public SessionUserMessageUpdateResult submitUserMessage(String workflowId, UserMessage message) {
            return existingWorkflowStub(workflowId).submitUserMessage(message);
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
        public void endHumanHandoff(String workflowId) {
            existingWorkflowStub(workflowId).endHumanHandoff();
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
    }
}
