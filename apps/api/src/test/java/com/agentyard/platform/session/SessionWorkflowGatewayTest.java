package com.agentyard.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.session.SessionContracts.UserTurn;
import com.agentyard.contracts.session.SessionContracts.UserTurnAcceptedResult;
import com.agentyard.platform.shared.ConflictException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.WorkflowUpdateTimeoutOrCancelledException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionWorkflowGatewayTest {
    @Test
    void submitUserTurn_shouldWaitForAcceptedUpdateWithoutResult() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowStub workflowStub = mock(WorkflowStub.class);
        @SuppressWarnings("unchecked")
        WorkflowUpdateHandle<UserTurnAcceptedResult> updateHandle = mock(WorkflowUpdateHandle.class);
        SessionWorkflowGateway.TemporalSessionWorkflowGateway gateway = new SessionWorkflowGateway.TemporalSessionWorkflowGateway(
            workflowClient,
            "session-task-queue"
        );
        UserTurn turn = new UserTurn("turn-1", "customer-1", "dedup-1", List.of(), Map.of());

        when(workflowClient.newUntypedWorkflowStub("session-1")).thenReturn(workflowStub);
        when(workflowStub.<UserTurnAcceptedResult>startUpdate(anyUpdateOptions(), eq(turn))).thenReturn(updateHandle);

        gateway.submitUserTurn("session-1", "turn-1", turn);

        ArgumentCaptor<UpdateOptions<UserTurnAcceptedResult>> optionsCaptor = updateOptionsCaptor();
        verify(workflowStub).startUpdate(optionsCaptor.capture(), eq(turn));
        assertEquals("submitUserTurn", optionsCaptor.getValue().getUpdateName());
        assertEquals("turn-1", optionsCaptor.getValue().getUpdateId());
        assertEquals(WorkflowUpdateStage.ACCEPTED, optionsCaptor.getValue().getWaitForStage());
        assertEquals(UserTurnAcceptedResult.class, optionsCaptor.getValue().getResultClass());
        verifyNoInteractions(updateHandle);
    }

    @Test
    void submitUserTurn_shouldTranslateTemporalUpdateTimeoutIntoConflict() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowStub workflowStub = mock(WorkflowStub.class);
        SessionWorkflowGateway.TemporalSessionWorkflowGateway gateway = new SessionWorkflowGateway.TemporalSessionWorkflowGateway(
            workflowClient,
            "session-task-queue"
        );
        UserTurn turn = new UserTurn("turn-1", "customer-1", "dedup-1", List.of(), Map.of());

        when(workflowClient.newUntypedWorkflowStub("session-1")).thenReturn(workflowStub);
        when(workflowStub.<UserTurnAcceptedResult>startUpdate(anyUpdateOptions(), eq(turn))).thenThrow(
            new WorkflowUpdateTimeoutOrCancelledException(
                WorkflowExecution.newBuilder().setWorkflowId("session-1").setRunId("run-1").build(),
                "update-1",
                "SessionWorkflow",
                null
            )
        );

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> gateway.submitUserTurn("session-1", "turn-1", turn)
        );

        assertEquals("session turn acceptance timed out", error.getMessage());
    }

    private static UpdateOptions<UserTurnAcceptedResult> anyUpdateOptions() {
        return any();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static ArgumentCaptor<UpdateOptions<UserTurnAcceptedResult>> updateOptionsCaptor() {
        return ArgumentCaptor.forClass((Class) UpdateOptions.class);
    }
}
