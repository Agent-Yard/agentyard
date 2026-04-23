package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.platform.shared.ConflictException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.WorkflowUpdateTimeoutOrCancelledException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionWorkflowGatewayTest {
    @Test
    void submitUserMessage_shouldWaitForCompletedUpdateWithConfiguredTimeout() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowStub workflowStub = mock(WorkflowStub.class);
        @SuppressWarnings("unchecked")
        WorkflowUpdateHandle<SessionUserMessageUpdateResult> updateHandle = mock(WorkflowUpdateHandle.class);
        SessionWorkflowGateway.TemporalSessionWorkflowGateway gateway = new SessionWorkflowGateway.TemporalSessionWorkflowGateway(
            workflowClient,
            "session-task-queue",
            Duration.ofSeconds(45)
        );
        UserMessage message = new UserMessage("msg-1", "customer-1", textMessageInput("hello"));
        SessionUserMessageUpdateResult expected = new SessionUserMessageUpdateResult(
            SessionMessageDeliveryStatus.ACCEPTED,
            "session-1",
            null
        );

        when(workflowClient.newUntypedWorkflowStub("session-1")).thenReturn(workflowStub);
        when(workflowStub.startUpdate(any(UpdateOptions.class), eq(message))).thenReturn(updateHandle);
        when(updateHandle.getResult(45_000L, TimeUnit.MILLISECONDS)).thenReturn(expected);

        SessionUserMessageUpdateResult result = gateway.submitUserMessage("session-1", message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateOptions<SessionUserMessageUpdateResult>> optionsCaptor = ArgumentCaptor.forClass((Class) UpdateOptions.class);
        verify(workflowStub).startUpdate(optionsCaptor.capture(), eq(message));
        assertEquals(expected, result);
        assertEquals("submitUserMessage", optionsCaptor.getValue().getUpdateName());
        assertEquals(WorkflowUpdateStage.ACCEPTED, optionsCaptor.getValue().getWaitForStage());
        assertEquals(SessionUserMessageUpdateResult.class, optionsCaptor.getValue().getResultClass());
        verify(updateHandle).getResult(45_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void submitUserMessage_shouldTranslateTemporalUpdateTimeoutIntoConflict() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowStub workflowStub = mock(WorkflowStub.class);
        @SuppressWarnings("unchecked")
        WorkflowUpdateHandle<SessionUserMessageUpdateResult> updateHandle = mock(WorkflowUpdateHandle.class);
        SessionWorkflowGateway.TemporalSessionWorkflowGateway gateway = new SessionWorkflowGateway.TemporalSessionWorkflowGateway(
            workflowClient,
            "session-task-queue",
            Duration.ofSeconds(30)
        );
        UserMessage message = new UserMessage("msg-1", "customer-1", textMessageInput("hello"));

        when(workflowClient.newUntypedWorkflowStub("session-1")).thenReturn(workflowStub);
        when(workflowStub.startUpdate(any(UpdateOptions.class), eq(message))).thenReturn(updateHandle);
        when(updateHandle.getResult(30_000L, TimeUnit.MILLISECONDS)).thenThrow(
            new WorkflowUpdateTimeoutOrCancelledException(
                WorkflowExecution.newBuilder().setWorkflowId("session-1").setRunId("run-1").build(),
                "update-1",
                "SessionWorkflow",
                null
            )
        );

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> gateway.submitUserMessage("session-1", message)
        );

        assertEquals("session message processing timed out", error.getMessage());
    }

    private static SessionMessageInput textMessageInput(String text) {
        return new SessionMessageInput(
            List.of(Map.of("type", "TEXT", "text", text)),
            Map.of()
        );
    }
}
