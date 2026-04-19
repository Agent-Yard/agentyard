package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import io.temporal.activity.ActivityInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ActivityInterface
public interface PlaybookNodeActivities {
    PlaybookNodeExecutionResult executeStep(PlaybookNodeExecutionRequest request);

    PlaybookNodeExecutionResult executeTool(PlaybookNodeExecutionRequest request);

    record PlaybookNodeExecutionRequest(
        String sessionId,
        String playbookRunId,
        String playbookId,
        String nodeKey,
        String nodeName,
        AgentConfig ownerAgent,
        String scriptRef,
        String scriptVersion,
        String toolId,
        String toolOperation,
        Map<String, Object> input,
        Map<String, Object> config
    ) {
        public PlaybookNodeExecutionRequest {
            input = immutableObjectMap(input);
            config = immutableObjectMap(config);
        }
    }

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
