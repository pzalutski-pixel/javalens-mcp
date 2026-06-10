package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.javalens.core.IJdtService;
import org.javalens.core.graph.ProjectGraph;
import org.javalens.core.graph.ProjectGraph.GraphNode;
import org.javalens.core.graph.ProjectGraph.NodeKind;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Project-wide dead-code detection over the whole-program reachability graph.
 * Unlike find_unused_code (private members, single file at a time), this walks
 * the call graph from declared entry points and reports public/package members
 * nothing reaches.
 */
public class FindUnreachableCodeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindUnreachableCodeTool.class);

    public FindUnreachableCodeTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_unreachable_code";
    }

    @Override
    public String getDescription() {
        return """
            Find code unreachable from any entry point, project-wide.

            USAGE: find_unreachable_code()
            OUTPUT: Members (types, methods, fields) that no entry point reaches,
            with visibility and location, plus the roots used.

            Roots are public static void main(String[]) methods and detected
            test methods (JUnit 4/5, TestNG; disabled tests still count).
            Reachability follows calls, instantiations, field accesses, field
            initializers, and overrides (a call through an interface or
            superclass reaches every override). A type is reported only when
            neither it nor any of its members is reachable.

            IMPORTANT: results mean "unreachable from declared entry points",
            not "safe to delete" - reflection, dependency injection, and
            serialization entry points are invisible to the graph.

            Options:
            - includeTestRoots: count test methods as entry points (default true)
            - maxResults: cap the reported list (default 100)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("includeTestRoots", "boolean", "Treat test methods as entry points (default true)")
            .optional("maxResults", "integer", "Maximum entries to return (default 100)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        boolean includeTestRoots = getBooleanParam(arguments, "includeTestRoots", true);
        int maxResults = getIntParam(arguments, "maxResults", 100);
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults", "must be >= 0");
        }

        try {
            ProjectGraph graph = service.getProjectGraphService().getGraph();

            Set<String> roots = new LinkedHashSet<>(graph.mainMethodKeys());
            int testMethodCount = 0;
            if (includeTestRoots) {
                for (TestMethodDetector.TestMethod test : TestMethodDetector.collectTestMethods(service)) {
                    testMethodCount++;
                    if (test.element() != null) {
                        String key = graph.keyOf(test.element());
                        if (key != null && graph.node(key) != null) {
                            roots.add(key);
                        }
                    }
                }
            }

            Set<String> reachable = graph.reachableFrom(roots);

            // Types are suppressed while any declared member remains reachable.
            Set<String> typesWithReachableMember = new HashSet<>();
            for (NodeKind kind : List.of(NodeKind.METHOD, NodeKind.FIELD)) {
                for (GraphNode node : graph.nodes(kind)) {
                    if (reachable.contains(node.key()) && node.ownerKey() != null) {
                        typesWithReachableMember.add(node.ownerKey());
                    }
                }
            }

            List<Map<String, Object>> entries = new ArrayList<>();
            for (GraphNode node : graph.nodes(NodeKind.TYPE)) {
                if (!reachable.contains(node.key()) && !typesWithReachableMember.contains(node.key())) {
                    entries.add(entry(node, "type", service));
                }
            }
            for (GraphNode node : graph.nodes(NodeKind.METHOD)) {
                if (!reachable.contains(node.key())) {
                    entries.add(entry(node, "method", service));
                }
            }
            for (GraphNode node : graph.nodes(NodeKind.FIELD)) {
                if (!reachable.contains(node.key())) {
                    entries.add(entry(node, "field", service));
                }
            }
            entries.sort(Comparator
                .comparing((Map<String, Object> e) -> (String) e.get("filePath"))
                .thenComparing(e -> (Integer) e.get("line"))
                .thenComparing(e -> (String) e.get("key")));

            int total = entries.size();
            boolean truncated = total > maxResults;
            List<Map<String, Object>> returned = truncated ? entries.subList(0, maxResults) : entries;

            Map<String, Object> rootInfo = new LinkedHashMap<>();
            rootInfo.put("mainMethods", graph.mainMethodKeys().stream().sorted().toList());
            rootInfo.put("testMethodCount", testMethodCount);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roots", rootInfo);
            data.put("unreachableCount", total);
            data.put("unreachable", returned);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned.size())
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "find_references to double-check a member before removing it",
                    "find_affected_tests to see what tests cover surviving code",
                    "analyze_change_impact for the blast radius of a removal"))
                .build());

        } catch (Exception e) {
            log.error("Error finding unreachable code: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> entry(GraphNode node, String kind, IJdtService service) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("key", node.key());
        entry.put("visibility", visibility(node.flags()));
        entry.put("filePath", service.getPathUtils().formatPath(node.filePath()));
        entry.put("line", node.line());
        return entry;
    }

    private static String visibility(int flags) {
        if (Flags.isPublic(flags)) {
            return "public";
        }
        if (Flags.isProtected(flags)) {
            return "protected";
        }
        if (Flags.isPrivate(flags)) {
            return "private";
        }
        return "package";
    }
}
