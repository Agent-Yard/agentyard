package com.lynxus.platform.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeAction;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantRunWorkflowGatewayTest {

    @Test
    void shouldStartWorkflowWithoutWaitingForFirstResult() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        AssistantRunWorkflow workflow = mock(AssistantRunWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(AssistantRunWorkflow.class), any(WorkflowOptions.class))).thenReturn(workflow);

        AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway gateway =
            new AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway(workflowClient, "lynxus-task-queue");

        gateway.start(sampleRequest("wf-123"));

        verify(workflowClient).newWorkflowStub(eq(AssistantRunWorkflow.class), any(WorkflowOptions.class));
        verify(workflow, never()).currentResult();
    }

    @Test
    void shouldUseExistingWorkflowStubWhenSubmittingResumeAction() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        AssistantRunWorkflow existingWorkflow = mock(AssistantRunWorkflow.class);
        when(workflowClient.newWorkflowStub(AssistantRunWorkflow.class, "wf-123")).thenReturn(existingWorkflow);

        AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway gateway =
            new AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway(workflowClient, "lynxus-task-queue");

        gateway.submitResumeAction(
            "wf-123",
            new ResumeAction(WorkflowContracts.ResumeActionType.CONTINUE, WorkflowContracts.ResumeSource.HUMAN, "人工已处理", "user-1", Map.of())
        );

        verify(workflowClient).newWorkflowStub(AssistantRunWorkflow.class, "wf-123");
        verify(workflowClient, never()).newWorkflowStub(eq(AssistantRunWorkflow.class), any(WorkflowOptions.class));
        verify(existingWorkflow).submitResumeAction(any(ResumeAction.class));
    }

    private WorkflowContracts.WorkflowStartRequest sampleRequest(String workflowId) {
        return new WorkflowContracts.WorkflowStartRequest(
            "task-" + workflowId,
            workflowId,
            "scenario-customer-ops",
            "怎么重置密码",
            "tester",
            null,
            null,
            new WorkflowContracts.LogContext(
                "0123456789abcdef0123456789abcdef",
                "session-" + workflowId,
                workflowId,
                "customer-1",
                "user-1"
            )
        );
    }
}
