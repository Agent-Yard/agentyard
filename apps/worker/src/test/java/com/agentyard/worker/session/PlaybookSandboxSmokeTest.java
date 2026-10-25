package com.agentyard.worker.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookRunStatus;
import com.agentyard.worker.runtime.SandboxGateway;
import com.agentyard.worker.runtime.AgentRuntimeGateway;
import com.agentyard.worker.runtime.ExecutionControlPlane;
import com.agentyard.contracts.agent.AgentContracts.ToolRunNodeRequest;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "AGENTYARD_SANDBOX_SMOKE_URL", matches = ".+")
class PlaybookSandboxSmokeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionControlPlane control = mock(ExecutionControlPlane.class);
    private final PlaybookNodeActivitiesImpl activities = new PlaybookNodeActivitiesImpl(
        new SandboxGateway.HttpSandboxGateway(System.getenv("AGENTYARD_SANDBOX_SMOKE_URL"), objectMapper),
        mock(AgentRuntimeGateway.class),
        control,
        objectMapper
    );

    @ParameterizedTest
    @ValueSource(strings = {"python", "node"})
    void shouldExecuteVersionedStepAndReturnStateAndRoute(String runtime) {
        String code = "python".equals(runtime) ? """
            print("processing refund")
            result = {"statePatch": {"amount": input_data["amount"] * 2, "label": input_data["label"],
                      "script": script_ref, "version": script_version}, "routeKey": "approved"}
            """ : """
            console.log("processing refund");
            result = {statePatch: {amount: inputData.amount * 2, label: inputData.label,
                      script: scriptRef, version: scriptVersion}, routeKey: "approved"};
            """;

        var result = activities.executeStep(request(runtime, code));

        assertThat(result.failureReason()).isNull();
        assertThat(result.terminalStatus()).isNull();
        assertThat(result.statePatch()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "amount", 42, "label", "退款审核", "script", "refund.normalize", "version", "2026.10.04"
        ));
        assertThat(result.routeKey()).isEqualTo("approved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"python", "node"})
    void shouldReturnFailedStepWithScriptError(String runtime) {
        String code = "python".equals(runtime)
            ? "raise ValueError('agentyard-script-failure')"
            : "throw new Error('agentyard-script-failure');";

        var result = activities.executeStep(request(runtime, code));

        assertThat(result.terminalStatus()).isEqualTo(PlaybookRunStatus.FAILED);
        assertThat(result.failureReason()).contains("agentyard-script-failure");
        assertThat(result.statePatch()).isEmpty();
    }

    private ToolRunNodeRequest request(String runtime, String code) {
        var node = ToolRunFixtures.script("normalize", "refund.normalize", "2026.10.04",
            Map.of("scriptVersions", Map.of("2026.10.04", Map.of("runtime", runtime, "code", code))));
        when(control.authorizeNode("run-1", "normalize", "run-1:normalize:1"))
            .thenReturn(ToolRunFixtures.runtime(ToolRunFixtures.flow(node)));
        return new ToolRunNodeRequest("run-1", "normalize", "run-1:normalize:1", Map.of("amount", 21, "label", "退款审核"));
    }
}
