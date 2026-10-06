package org.example.indexing;

import java.util.List;
import java.util.Map;

/**
 * Public type whose nested record is referenced from both of its package neighbours.
 */
public final class Graph {

    public enum Kind { TYPE, METHOD }

    public record Node(String key, Kind kind) {
    }

    private final Map<String, Node> nodes;

    Graph(Map<String, Node> nodes) {
        this.nodes = nodes;
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }
}
