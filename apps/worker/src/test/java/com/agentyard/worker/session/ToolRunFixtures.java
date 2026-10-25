package com.agentyard.worker.session;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.runtime.RuntimeContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class ToolRunFixtures {
    static ToolRunRuntime runtime(PlaybookConfig flow) { return runtime("run-1", flow); }
    static ToolRunRuntime runtime(String id, PlaybookConfig flow) {
        return new ToolRunRuntime(new ToolRun(id, "session-1", "participant-1", "activation-1", "task-1",
            "playbook-version-1", ToolRunStatus.RUNNING, Map.of("amount", 21, "label", "退款审核"), Map.of(), Map.of(), null,
            Instant.EPOCH, Instant.EPOCH), flow, null);
    }
    static PlaybookConfig flow(PlaybookNode... nodes) {
        var edges = new java.util.ArrayList<PlaybookEdge>();
        for (int i = 0; i + 1 < nodes.length; i++) {
            edges.add(new PlaybookEdge("edge-" + i, nodes[i].nodeKey(), nodes[i+1].nodeKey(), null, "", true));
        }
        return new PlaybookConfig("flow-1", "Flow", "Flow", "{}", "{}", null, true, true,
            nodes[0].nodeKey(), List.of(nodes), edges);
    }
    static PlaybookNode node(String key, PlaybookNodeType type) {
        return new PlaybookNode(key, key, type, key, null, null, type == PlaybookNodeType.TOOL_TASK ? "tool-1" : null,
            type == PlaybookNodeType.TOOL_TASK ? "invoke" : null, Map.of(), null);
    }
    static PlaybookNode script(String key, String ref, String version, Map<String, Object> config) {
        return new PlaybookNode(key, key, PlaybookNodeType.STEP, key, ref, version, null, null, config, null);
    }
}
