package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookEdge;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlaybookDefinition {
    private final PlaybookConfig playbook;
    private final Map<String, PlaybookNode> nodesByKey;
    private final Map<String, List<PlaybookEdge>> outgoingEdgesByNodeKey;

    PlaybookDefinition(PlaybookConfig playbook) {
        this.playbook = playbook;
        this.nodesByKey = new LinkedHashMap<>();
        playbook.nodes().forEach(node -> nodesByKey.put(node.nodeKey(), node));
        this.outgoingEdgesByNodeKey = new LinkedHashMap<>();
        playbook.edges().forEach(edge -> outgoingEdgesByNodeKey.computeIfAbsent(edge.sourceNodeKey(), ignored -> new java.util.ArrayList<>()).add(edge));
    }

    PlaybookNode entryNode() {
        return requireNode(playbook.entryNodeKey());
    }

    PlaybookNode requireNode(String nodeKey) {
        PlaybookNode node = nodesByKey.get(nodeKey);
        if (node == null) {
            throw new IllegalStateException("missing playbook node: " + nodeKey);
        }
        return node;
    }

    String nextNodeKey(String currentNodeKey, String routeKey) {
        List<PlaybookEdge> edges = outgoingEdgesByNodeKey.getOrDefault(currentNodeKey, List.of());
        if (edges.isEmpty()) {
            return null;
        }
        if (routeKey != null && !routeKey.isBlank()) {
            for (PlaybookEdge edge : edges) {
                if (routeKey.equals(edge.routeKey())) {
                    return edge.targetNodeKey();
                }
            }
        }
        for (PlaybookEdge edge : edges) {
            if (edge.defaultEdge()) {
                return edge.targetNodeKey();
            }
        }
        if (edges.size() == 1) {
            return edges.get(0).targetNodeKey();
        }
        throw new IllegalStateException("unable to resolve next node for " + currentNodeKey + " routeKey=" + routeKey);
    }
}
