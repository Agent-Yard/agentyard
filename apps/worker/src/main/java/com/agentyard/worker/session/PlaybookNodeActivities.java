package com.agentyard.worker.session;

import com.agentyard.contracts.agent.AgentContracts.ToolRunNodeRequest;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookRunStatus;
import io.temporal.activity.ActivityInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ActivityInterface
public interface PlaybookNodeActivities {
    PlaybookNodeExecutionResult executeStep(ToolRunNodeRequest request);

    PlaybookNodeExecutionResult executeTool(ToolRunNodeRequest request);

    record PlaybookNodeExecutionResult(
        Map<String, Object> statePatch,
        String routeKey,
        PlaybookRunStatus terminalStatus,
        String failureReason
    ) {
        public PlaybookNodeExecutionResult {
            statePatch = immutableObjectMap(statePatch);
        }
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
