package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.runtime.RuntimeContracts.*;
import com.agentyard.worker.runtime.AgentRuntimeGateway;
import com.agentyard.worker.runtime.ExecutionControlPlane;
import com.agentyard.worker.runtime.SandboxGateway;
import com.agentyard.worker.runtime.WorkerHttpClient.WorkerHttpException;
import com.agentyard.worker.runtime.ExecutionPaused;
import io.temporal.failure.ApplicationFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PlaybookNodeActivitiesImplTest {
    private final SandboxGateway sandbox = mock(SandboxGateway.class);
    private final AgentRuntimeGateway runtime = mock(AgentRuntimeGateway.class);
    private final ExecutionControlPlane control = mock(ExecutionControlPlane.class);
    private final PlaybookNodeActivitiesImpl activities = new PlaybookNodeActivitiesImpl(sandbox, runtime, control, new ObjectMapper());

    @Test
    void checksCurrentAuthorizationBeforeRunningVersionedScript() {
        var node = ToolRunFixtures.script("normalize", "refund.normalize", "v1",
            Map.of("scriptVersions", Map.of("v1", Map.of("runtime", "python", "code", "result = {'statePatch': {'approved': True}}"))));
        when(control.authorizeNode("run-1", "normalize", "run-1:normalize:1")).thenReturn(ToolRunFixtures.runtime(ToolRunFixtures.flow(node)));
        when(sandbox.executePython(anyString(), anyString(), anyInt())).thenReturn(
            new SandboxGateway.SandboxExecutionResult("ok", "__AGENTYARD_RESULT__{\"statePatch\":{\"approved\":true}}", ""));
        var request = new ToolRunNodeRequest("run-1", "normalize", "run-1:normalize:1", Map.of("amount", 10));
        var result = activities.executeStep(request);
        assertNull(result.terminalStatus());
        assertEquals(true, result.statePatch().get("approved"));
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(sandbox).executePython(source.capture(), anyString(), anyInt());
        assertTrue(source.getValue().contains("refund.normalize"));
        assertTrue(source.getValue().contains("v1"));
        when(control.authorizeNode("run-1", "normalize", "run-1:normalize:1")).thenThrow(new IllegalStateException("permission revoked"));
        assertEquals(PlaybookRunStatus.FAILED, activities.executeStep(request).terminalStatus());
        verify(sandbox, times(1)).executePython(anyString(), anyString(), anyInt());
    }

    @Test
    void pauseIsReportedBeforeAnySandboxOrToolEffectWithoutActivityRetry() {
        var paused=new WorkerHttpException(423,"/authorize-node");
        when(control.authorizeNode(anyString(),anyString(),anyString())).thenThrow(paused);
        var request=new ToolRunNodeRequest("run","node","call",Map.of());
        for(var execute:java.util.List.<java.util.function.Function<ToolRunNodeRequest,PlaybookNodeActivities.PlaybookNodeExecutionResult>>of(activities::executeStep,activities::executeTool)) {
            var failure=assertThrows(ApplicationFailure.class,()->execute.apply(request));
            assertEquals(ExecutionPaused.TYPE,failure.getType());assertTrue(failure.isNonRetryable());
        }
        verifyNoInteractions(sandbox,runtime);
    }

    @Test
    void rejectsUnversionedOrMissingScriptWithoutSideEffect() {
        var node = ToolRunFixtures.script("normalize", "refund.normalize", "v1", Map.of());
        when(control.authorizeNode(anyString(), anyString(), anyString())).thenReturn(ToolRunFixtures.runtime(ToolRunFixtures.flow(node)));
        var result = activities.executeStep(new ToolRunNodeRequest("run-1", "normalize", "call-1", Map.of()));
        assertEquals(PlaybookRunStatus.FAILED, result.terminalStatus());
        assertTrue(result.failureReason().contains("scriptVersions"));
        verifyNoInteractions(sandbox);
    }

    @Test
    void rejectsWrongNodeTypeBeforeSandboxExecution() {
        var node = ToolRunFixtures.node("tool", PlaybookNodeType.TOOL_TASK);
        when(control.authorizeNode(anyString(), anyString(), anyString())).thenReturn(ToolRunFixtures.runtime(ToolRunFixtures.flow(node)));
        var result = activities.executeStep(new ToolRunNodeRequest("run-1", "tool", "call-1", Map.of()));
        assertEquals(PlaybookRunStatus.FAILED, result.terminalStatus());
        verifyNoInteractions(sandbox);
    }

    @Test
    void externalToolRunsWithOnlyTrustedRunReferenceAndRejectsForgedCancellation() {
        var node = ToolRunFixtures.node("tool", PlaybookNodeType.TOOL_TASK);
        when(control.authorizeNode(anyString(), anyString(), anyString())).thenReturn(ToolRunFixtures.runtime(ToolRunFixtures.flow(node)));
        when(runtime.executeToolNode(any())).thenReturn(new PlaybookToolTaskResult(Map.of(), null, PlaybookRunStatus.CANCELLED, "cancel"));
        var request = new ToolRunNodeRequest("run-1", "tool", "call-1", Map.of());
        var result = activities.executeTool(request);
        assertEquals(PlaybookRunStatus.FAILED, result.terminalStatus());
        verify(runtime).executeToolNode(request);
    }
}
