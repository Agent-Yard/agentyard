package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanAction;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantRunWorkflowGatewayTest {

    @Test
    void shouldUseExistingWorkflowStubWhenSubmittingHumanAction() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        AssistantRunWorkflow existingWorkflow = mock(AssistantRunWorkflow.class);
        WorkflowResult expected = new WorkflowResult(
            "wf-123",
            WorkflowStatus.COMPLETED,
            "人工处理已完成。",
            "人工处理已完成，已同步客户。",
            "end",
            null,
            null,
            List.of(new NodeSnapshot("end", "结束", NodeStatus.COMPLETED, "人工已处理", Instant.now())),
            List.of(),
            false,
            null
        );
        when(workflowClient.newWorkflowStub(AssistantRunWorkflow.class, "wf-123")).thenReturn(existingWorkflow);
        when(existingWorkflow.currentResult()).thenReturn(expected);

        AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway gateway =
            new AssistantRunWorkflowGateway.TemporalAssistantRunWorkflowGateway(workflowClient, "lynxus-task-queue");

        WorkflowResult actual = gateway.submitHumanActionAndAwaitResult(
            "wf-123",
            new HumanAction("CONFIRM", "人工已处理", "operator-1", Map.of())
        );

        assertSame(expected, actual);
        verify(workflowClient).newWorkflowStub(AssistantRunWorkflow.class, "wf-123");
        verify(workflowClient, never()).newWorkflowStub(eq(AssistantRunWorkflow.class), any(WorkflowOptions.class));
        verify(existingWorkflow).submitHumanAction(any(HumanAction.class));
        verify(existingWorkflow).currentResult();
    }
}
