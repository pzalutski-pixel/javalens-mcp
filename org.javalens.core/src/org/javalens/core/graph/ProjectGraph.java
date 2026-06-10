package org.javalens.core.graph;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable whole-project graph over source types, methods, and fields.
 *
 * <p>Node keys are human-readable and deterministic:
 * <ul>
 *   <li>type — {@code com.example.Foo}</li>
 *   <li>method — {@code com.example.Foo#bar(String,int)} (constructors use the
 *       simple type name: {@code com.example.Foo#Foo()})</li>
 *   <li>field — {@code com.example.Foo#count}</li>
 * </ul>
 *
 * <p>Edge ownership: edges discovered in a method body belong to that method;
 * edges in field initializers or initializer blocks belong to the declaring
 * type node (they fire when the type is reached). Instantiation edges target
 * the constructor node when one is declared, the type node when the
 * constructor is implicit.
 *
 * <p>Reachability uses class-hierarchy expansion: reaching a method also
 * reaches every source method that overrides it (transitively). The reverse
 * closure ({@link #transitiveCallers}) climbs override declarations silently —
 * callers of an overridden declaration are callers of the override — and hops
 * through type nodes silently (creators of a type "call" its initializers).
 * Only concrete calling methods are reported.
 */
public final class ProjectGraph {

    public enum NodeKind { TYPE, METHOD, FIELD }

    public enum EdgeKind { CALLS, CREATES, READS, WRITES }

    /**
     * @param key       graph key (see class Javadoc for the format)
     * @param kind      node kind
     * @param ownerKey  declaring type key for methods/fields, {@code null} for types
     * @param simpleName declaration simple name
     * @param flags     JDT {@link org.eclipse.jdt.core.Flags}-compatible modifiers
     * @param filePath  absolute path of the declaring source file
     * @param line      zero-based line of the declaration name
     */
    public record GraphNode(String key, NodeKind kind, String ownerKey, String simpleName,
                            int flags, String filePath, int line) {
    }

    public record GraphEdge(String fromKey, String toKey, EdgeKind kind) {
    }

    private final Map<String, GraphNode> nodesByKey;
    private final List<GraphEdge> edges;
    /** override method key -> the declarations it directly overrides. */
    private final Map<String, Set<String>> overrides;
    /** overridden declaration key -> the methods that directly override it. */
    private final Map<String, Set<String>> overriddenBy;
    private final Set<String> mainMethodKeys;
    private final Map<String, List<GraphEdge>> outgoing;
    private final Map<String, List<GraphEdge>> incoming;

    ProjectGraph(Map<String, GraphNode> nodesByKey, List<GraphEdge> edges,
                 Map<String, Set<String>> overrides, Set<String> mainMethodKeys) {
        this.nodesByKey = Map.copyOf(nodesByKey);
        this.edges = List.copyOf(edges);
        this.overrides = deepCopy(overrides);
        this.mainMethodKeys = Set.copyOf(mainMethodKeys);
        this.overriddenBy = invert(this.overrides);
        this.outgoing = edges.stream().collect(Collectors.groupingBy(GraphEdge::fromKey));
        this.incoming = edges.stream().collect(Collectors.groupingBy(GraphEdge::toKey));
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> map) {
        return map.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }

    private static Map<String, Set<String>> invert(Map<String, Set<String>> map) {
        return map.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(v -> Map.entry(v, e.getKey())))
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toUnmodifiableSet())));
    }

    public Collection<GraphNode> nodes(NodeKind kind) {
        return nodesByKey.values().stream().filter(n -> n.kind() == kind).toList();
    }

    public GraphNode node(String key) {
        return nodesByKey.get(key);
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    /** Override method key -> the declarations it directly overrides. */
    public Map<String, Set<String>> overrides() {
        return overrides;
    }

    /** Keys of {@code public static void main(String[])} methods. */
    public Set<String> mainMethodKeys() {
        return mainMethodKeys;
    }

    /**
     * Forward closure from the given root keys. The result contains: reached
     * methods, reached types (instantiated, or declaring a root method), and
     * fields read or written by a reached owner. Unknown root keys are ignored.
     */
    public Set<String> reachableFrom(Set<String> rootKeys) {
        Set<String> reached = new HashSet<>();
        Deque<String> work = new ArrayDeque<>();

        for (String root : rootKeys) {
            GraphNode node = nodesByKey.get(root);
            if (node == null) {
                continue;
            }
            enqueue(root, reached, work);
            if (node.kind() == NodeKind.METHOD && node.ownerKey() != null) {
                enqueue(node.ownerKey(), reached, work);
            }
        }

        while (!work.isEmpty()) {
            String key = work.pop();
            for (GraphEdge edge : outgoing.getOrDefault(key, List.of())) {
                GraphNode target = nodesByKey.get(edge.toKey());
                if (target == null) {
                    continue;
                }
                enqueue(edge.toKey(), reached, work);
                if (edge.kind() == EdgeKind.CREATES && target.kind() == NodeKind.METHOD
                    && target.ownerKey() != null) {
                    // Explicit constructor reached: the type's initializers run too.
                    enqueue(target.ownerKey(), reached, work);
                }
            }
        }
        return reached;
    }

    private void enqueue(String key, Set<String> reached, Deque<String> work) {
        if (!reached.add(key)) {
            return;
        }
        work.push(key);
        GraphNode node = nodesByKey.get(key);
        if (node != null && node.kind() == NodeKind.METHOD) {
            // Class-hierarchy expansion: reaching a method reaches its overrides.
            for (String override : overriddenBy.getOrDefault(key, Set.of())) {
                enqueue(override, reached, work);
            }
        }
    }

    /**
     * Reverse closure: every method from which the given node may be invoked
     * (or, for fields, accessed). Root-independent — callers inside otherwise
     * unreachable code are still reported. The target itself is not included.
     */
    public Set<String> transitiveCallers(String key) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> work = new ArrayDeque<>();
        if (!nodesByKey.containsKey(key)) {
            return result;
        }
        visited.add(key);
        work.push(key);

        while (!work.isEmpty()) {
            String current = work.pop();
            for (GraphEdge edge : incoming.getOrDefault(current, List.of())) {
                GraphNode from = nodesByKey.get(edge.fromKey());
                if (from == null || !visited.add(edge.fromKey())) {
                    continue;
                }
                if (from.kind() == NodeKind.METHOD) {
                    result.add(edge.fromKey());
                }
                work.push(edge.fromKey());
            }
            GraphNode node = nodesByKey.get(current);
            if (node != null && node.kind() == NodeKind.METHOD) {
                // Callers of the declarations this method overrides reach it too.
                for (String overridden : overrides.getOrDefault(current, Set.of())) {
                    if (visited.add(overridden)) {
                        work.push(overridden);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Compute the graph key for a Java model element. Returns the key whether
     * or not the element is present in the graph; callers check {@link #node}.
     * Returns {@code null} for element kinds the graph does not model.
     */
    public String keyOf(IJavaElement element) throws JavaModelException {
        if (element instanceof IType type) {
            return type.getFullyQualifiedName('.');
        }
        if (element instanceof IMethod method) {
            String typeKey = method.getDeclaringType().getFullyQualifiedName('.');
            String name = method.isConstructor()
                ? method.getDeclaringType().getElementName()
                : method.getElementName();
            String params = java.util.Arrays.stream(method.getParameterTypes())
                .map(Signature::getSignatureSimpleName)
                .collect(Collectors.joining(","));
            return typeKey + "#" + name + "(" + params + ")";
        }
        if (element instanceof IField field) {
            return field.getDeclaringType().getFullyQualifiedName('.') + "#" + field.getElementName();
        }
        return null;
    }
}
