package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class JdbcRuntimeRepositoryJsonCodecTest {
    private final JdbcRuntimeRepository repository = new JdbcRuntimeRepository(new JdbcTemplate(), mapper());

    @Test
    void shouldRoundTripComplexJsonPayloads() {
        SharedSessionState state = new SharedSessionState(
            Map.of("conversation", Map.of("turn", 2)),
            Map.of("artifact", Map.of("type", "tool")),
            Map.of("agent-a", Map.of("status", "done"))
        );
        String payload = repository.writeJson(state);

        SharedSessionState restored = repository.readJson(payload, SharedSessionState.class);

        assertNotNull(restored);
        assertEquals(Map.of("turn", 2), restored.facts().get("conversation"));
        assertEquals(Map.of("status", "done"), restored.agentScopes().get("agent-a"));
    }

    @Test
    void shouldRoundTripJsonCollections() {
        List<ExecutionCheckpoint> checkpoints = List.of(
            new ExecutionCheckpoint("cp-1", "resume", "node-a", "{\"cursor\":1}", null, 1),
            new ExecutionCheckpoint("cp-2", "resume", "node-b", "{\"cursor\":2}", null, 2)
        );
        String payload = repository.writeJson(checkpoints);

        List<ExecutionCheckpoint> restored = repository.readJson(payload, new TypeReference<List<ExecutionCheckpoint>>() {
        });

        assertEquals(2, restored.size());
        assertEquals("node-b", restored.getLast().waitingNodeKey());
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
            .findAndAddModules()
            .build();
    }
}
