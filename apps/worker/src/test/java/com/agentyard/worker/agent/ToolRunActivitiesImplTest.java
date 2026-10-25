package com.agentyard.worker.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.agentyard.worker.runtime.ExecutionControlPlane;
import com.agentyard.worker.runtime.ExecutionPaused;
import com.agentyard.worker.runtime.WorkerHttpClient.WorkerHttpException;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

class ToolRunActivitiesImplTest {
    @Test void pausedAndRevokedLoadsLeaveActivityRetryWhileTransportFailuresRemainRetryable() {
        var control=mock(ExecutionControlPlane.class);var activities=new ToolRunActivitiesImpl(control);
        doThrow(new WorkerHttpException(423,"/runtime")).when(control).toolRun("run");
        var pause=assertThrows(ApplicationFailure.class,()->activities.loadToolRun("run"));
        assertEquals(ExecutionPaused.TYPE,pause.getType());assertTrue(pause.isNonRetryable());
        doThrow(new WorkerHttpException(403,"/runtime")).when(control).toolRun("run");
        var denied=assertThrows(ApplicationFailure.class,()->activities.loadToolRun("run"));
        assertEquals("TOOL_RUN_UNAVAILABLE",denied.getType());assertTrue(denied.isNonRetryable());
        doThrow(new WorkerHttpException(503,"/runtime")).when(control).toolRun("run");
        assertEquals(503,assertThrows(WorkerHttpException.class,()->activities.loadToolRun("run")).statusCode());
    }
}
