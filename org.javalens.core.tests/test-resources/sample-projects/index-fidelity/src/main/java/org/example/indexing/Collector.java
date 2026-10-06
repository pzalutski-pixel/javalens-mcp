package org.example.indexing;

import org.example.indexing.Graph.Kind;
import org.example.indexing.Graph.Node;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.stream.Collectors;

/**
 * Package-private final class extending a JDK class, with a lambda, a method
 * reference and nested-type imports.
 */
final class Collector extends TimerTask {

    private final Map<String, Node> nodes;
    private final Map<String, Set<String>> edges;

    Collector(Map<String, Node> nodes, Map<String, Set<String>> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @Override
    public void run() {
        nodes.put("root", new Node("root", Kind.TYPE));
    }

    void link(String from, String to) {
        edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    String keys() {
        return nodes.values().stream()
            .map(Node::key)
            .collect(Collectors.joining(","));
    }
}
