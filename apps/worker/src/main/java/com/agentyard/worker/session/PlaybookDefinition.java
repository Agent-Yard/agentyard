package com.agentyard.worker.session;

import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookConfig;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookEdge;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class PlaybookDefinition {
    private final PlaybookConfig playbook;
    private final Map<String, PlaybookNode> nodesByKey;
    private final Map<String, List<PlaybookEdge>> outgoingEdgesByNodeKey;

    public PlaybookDefinition(PlaybookConfig playbook) {
        if (playbook == null || playbook.nodes().isEmpty()) {
            throw new IllegalArgumentException("playbook must contain nodes");
        }
        this.playbook = playbook;
        this.nodesByKey = new LinkedHashMap<>();
        for (PlaybookNode node : playbook.nodes()) {
            if (node.nodeKey() == null || node.nodeKey().isBlank()) {
                throw new IllegalArgumentException("playbook node key is required");
            }
            if (nodesByKey.putIfAbsent(node.nodeKey(), node) != null) {
                throw new IllegalArgumentException("duplicate playbook node: " + node.nodeKey());
            }
        }
        requireNode(playbook.entryNodeKey());
        this.outgoingEdgesByNodeKey = new LinkedHashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        for (PlaybookEdge edge : playbook.edges()) {
            requireNode(edge.sourceNodeKey());
            requireNode(edge.targetNodeKey());
            if (edge.edgeKey() == null || edge.edgeKey().isBlank() || !edgeKeys.add(edge.edgeKey())) {
                throw new IllegalArgumentException("playbook edge keys must be present and unique");
            }
            List<PlaybookEdge> outgoing = outgoingEdgesByNodeKey.computeIfAbsent(edge.sourceNodeKey(), ignored -> new java.util.ArrayList<>());
            if (outgoing.stream().anyMatch(existing -> edge.defaultEdge() && existing.defaultEdge()
                || edge.routeKey() != null && !edge.routeKey().isBlank() && edge.routeKey().equals(existing.routeKey()))) {
                throw new IllegalArgumentException("ambiguous playbook routes from node: " + edge.sourceNodeKey());
            }
            outgoing.add(edge);
        }
    }

    PlaybookNode entryNode() {
        return requireNode(playbook.entryNodeKey());
    }

    public PlaybookNode requireNode(String nodeKey) {
        PlaybookNode node = nodesByKey.get(nodeKey);
        if (node == null) {
            throw new IllegalStateException("missing playbook node: " + nodeKey);
        }
        return node;
    }

    public String nextNodeKey(String currentNodeKey, String routeKey) {
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
