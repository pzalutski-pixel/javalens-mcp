package org.example.indexing;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Package-private final class without lambdas or method references; its
 * callback is an anonymous class.
 */
final class Builder {

    private Builder() {
    }

    static Graph build() {
        Map<String, Graph.Node> nodes = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new HashMap<>();
        Collector collector = new Collector(nodes, edges);
        Runnable step = new Runnable() {
            @Override
            public void run() {
                collector.run();
            }
        };
        step.run();
        return new Graph(nodes);
    }
}
