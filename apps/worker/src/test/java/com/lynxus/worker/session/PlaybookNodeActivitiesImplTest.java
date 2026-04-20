package com.lynxus.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.worker.runtime.SandboxGateway;
import com.lynxus.worker.runtime.SessionAgentRuntimeGateway;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PlaybookNodeActivitiesImplTest {
    @Test
    void executeStep_shouldRejectMissingScriptIdentity() {
        SandboxGateway sandboxGateway = mock(SandboxGateway.class);
        PlaybookNodeActivitiesImpl activities = new PlaybookNodeActivitiesImpl(
            sandboxGateway,
            mock(SessionAgentRuntimeGateway.class),
            new ObjectMapper()
        );

        PlaybookNodeActivities.PlaybookNodeExecutionResult result = activities.executeStep(
            new PlaybookNodeActivities.PlaybookNodeExecutionRequest(
                "session-1",
                "run-1",
                "playbook-1",
                "step-1",
                "Step 1",
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of()
            )
        );

        assertEquals(PlaybookRunStatus.FAILED, result.terminalStatus());
        assertEquals("playbook step must define scriptRef and scriptVersion", result.failureReason());
        verify(sandboxGateway, never()).executePython(anyString(), anyString(), anyInt());
    }

    @Test
    void executeStep_shouldInjectScriptIdentityIntoSandboxPayload() {
        SandboxGateway sandboxGateway = mock(SandboxGateway.class);
        when(sandboxGateway.executePython(anyString(), anyString(), anyInt())).thenReturn(
            new SandboxGateway.SandboxExecutionResult(
                "ok",
                "__LYNXUS_RESULT__{\"statePatch\":{\"approved\":true},\"routeKey\":null}",
                ""
            )
        );
        PlaybookNodeActivitiesImpl activities = new PlaybookNodeActivitiesImpl(
            sandboxGateway,
            mock(SessionAgentRuntimeGateway.class),
            new ObjectMapper()
        );

        PlaybookNodeActivities.PlaybookNodeExecutionResult result = activities.executeStep(
            new PlaybookNodeActivities.PlaybookNodeExecutionRequest(
                "session-1",
                "run-1",
                "playbook-1",
                "step-1",
                "Step 1",
                null,
                "refund.normalize",
                "2026.04.20",
                null,
                null,
                Map.of("amount", 10),
                Map.of(
                    "scriptVersions",
                    Map.of(
                        "2026.04.20",
                        Map.of("runtime", "python", "code", "result = {'statePatch': {'approved': True}, 'routeKey': None}")
                    ),
                    "code",
                    "result = {'statePatch': {'approved': False}, 'routeKey': None}"
                )
            )
        );

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandboxGateway).executePython(scriptCaptor.capture(), anyString(), anyInt());
        assertTrue(scriptCaptor.getValue().contains("refund.normalize"));
        assertTrue(scriptCaptor.getValue().contains("2026.04.20"));
        assertTrue(scriptCaptor.getValue().contains("'approved': True"));
        assertTrue(scriptCaptor.getValue().contains("script_config"));
        assertEquals(null, result.terminalStatus());
        assertEquals(Boolean.TRUE, result.statePatch().get("approved"));
    }

    @Test
    void executeStep_shouldRejectMissingVersionedScriptConfig() {
        SandboxGateway sandboxGateway = mock(SandboxGateway.class);
        PlaybookNodeActivitiesImpl activities = new PlaybookNodeActivitiesImpl(
            sandboxGateway,
            mock(SessionAgentRuntimeGateway.class),
            new ObjectMapper()
        );

        PlaybookNodeActivities.PlaybookNodeExecutionResult result = activities.executeStep(
            new PlaybookNodeActivities.PlaybookNodeExecutionRequest(
                "session-1",
                "run-1",
                "playbook-1",
                "step-1",
                "Step 1",
                null,
                "refund.normalize",
                "2026.04.20",
                null,
                null,
                Map.of(),
                Map.of("scriptVersions", Map.of("2026.04.19", Map.of("runtime", "python", "code", "result = {}")))
            )
        );

        assertEquals(PlaybookRunStatus.FAILED, result.terminalStatus());
        assertEquals(
            "playbook step refund.normalize@2026.04.20 is missing versioned script config",
            result.failureReason()
        );
        verify(sandboxGateway, never()).executePython(anyString(), anyString(), anyInt());
    }
}
