package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.contracts.runtime.RuntimeContracts.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaybookDefinitionTest {
    @Test
    void preservesExplicitBranchAndDefaultRoute() {
        var definition = new PlaybookDefinition(config(List.of(node("start"), node("approved"), node("review")), List.of(
            new PlaybookEdge("approved", "start", "approved", "approved", "Approved", false),
            new PlaybookEdge("review", "start", "review", null, "Review", true))));
        assertEquals("approved", definition.nextNodeKey("start", "approved"));
        assertEquals("review", definition.nextNodeKey("start", "needs-review"));
    }

    @Test
    void rejectsAmbiguousOrBrokenGraphBeforeExecutingSideEffects() {
        assertThrows(IllegalArgumentException.class, () -> new PlaybookDefinition(config(
            List.of(node("start"), node("start")), List.of())));
        assertThrows(IllegalStateException.class, () -> new PlaybookDefinition(config(List.of(node("start")),
            List.of(new PlaybookEdge("missing", "start", "missing", null, "", true)))));
        assertThrows(IllegalArgumentException.class, () -> new PlaybookDefinition(config(List.of(node("start"), node("end")),
            List.of(new PlaybookEdge("a", "start", "end", null, "", true),
                new PlaybookEdge("b", "start", "end", null, "", true)))));
    }

    private PlaybookConfig config(List<PlaybookNode> nodes, List<PlaybookEdge> edges) {
        return new PlaybookConfig("flow", "Flow", "", "{}", "{}", null, true, true, "start", nodes, edges);
    }

    private PlaybookNode node(String key) {
        return new PlaybookNode(key, key, PlaybookNodeType.END, "", null, null, null, null, Map.of(), null);
    }
}
