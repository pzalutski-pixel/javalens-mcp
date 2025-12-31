package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Detect circular dependencies at package level.
 *
 * Uses Tarjan's SCC algorithm to find strongly connected components,
 * then extracts cycle paths for clear reporting.
 */
public class FindCircularDependenciesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindCircularDependenciesTool.class);

    public FindCircularDependenciesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_circular_dependencies";
    }

    @Override
    public String getDescription() {
        return """
            Detect cycles in packages.

            USAGE: find_circular_dependencies()
            USAGE: find_circular_dependencies(packageFilter="com.example")
            OUTPUT: List of circular dependency cycles

            Uses Tarjan's SCC algorithm to efficiently detect all cycles.
            Reports cycle paths and affected packages.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "packageFilter", Map.of(
                "type", "string",
                "description", "Package prefix to analyze (default: all project packages)"
            ),
            "maxCycleLength", Map.of(
                "type", "integer",
                "description", "Maximum cycle length to report (default: 10)"
            )
        ));
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String packageFilter = getStringParam(arguments, "packageFilter", null);
        int maxCycleLength = getIntParam(arguments, "maxCycleLength", 10);

        try {
            // Build package dependency graph
            Map<String, Set<String>> graph = buildPackageGraph(service, packageFilter);

            if (graph.isEmpty()) {
                return ToolResponse.success(Map.of(
                    "hasCycles", false,
                    "cycleCount", 0,
                    "cycles", List.of(),
                    "affectedPackages", List.of()
                ));
            }

            // Find strongly connected components using Tarjan's algorithm
            List<Set<String>> sccs = findSCCs(graph);

            // Filter to only cycles (SCCs with more than 1 node)
            List<Set<String>> cycles = sccs.stream()
                .filter(scc -> scc.size() > 1)
                .filter(scc -> scc.size() <= maxCycleLength)
                .collect(Collectors.toList());

            // Build detailed cycle information
            List<Map<String, Object>> cycleInfos = new ArrayList<>();
            Set<String> allAffectedPackages = new HashSet<>();

            for (Set<String> cycle : cycles) {
                List<String> cyclePath = findCyclePath(cycle, graph);
                allAffectedPackages.addAll(cycle);

                Map<String, Object> cycleInfo = new LinkedHashMap<>();
                cycleInfo.put("packages", new ArrayList<>(cycle));
                cycleInfo.put("path", String.join(" -> ", cyclePath));
                cycleInfo.put("length", cycle.size());
                cycleInfo.put("severity", cycle.size() <= 2 ? "medium" : "high");
                cycleInfos.add(cycleInfo);
            }

            // Generate suggestions
            List<String> suggestions = new ArrayList<>();
            if (!cycles.isEmpty()) {
                suggestions.add("Consider introducing interfaces to break dependency cycles");
                suggestions.add("Apply dependency inversion principle (DIP)");
                if (cycles.stream().anyMatch(c -> c.size() > 3)) {
                    suggestions.add("Large cycles may indicate architectural issues - consider package restructuring");
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hasCycles", !cycles.isEmpty());
            data.put("cycleCount", cycles.size());
            data.put("cycles", cycleInfos);
            data.put("affectedPackages", new ArrayList<>(allAffectedPackages));
            data.put("suggestions", suggestions);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(cycles.size())
                .returnedCount(cycles.size())
                .suggestedNextTools(cycles.isEmpty()
                    ? List.of("No circular dependencies found")
                    : List.of("get_dependency_graph to analyze specific packages"))
                .build());

        } catch (Exception e) {
            log.error("Error finding circular dependencies: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Build the package dependency graph from imports.
     */
    private Map<String, Set<String>> buildPackageGraph(IJdtService service, String packageFilter)
            throws Exception {
        Map<String, Set<String>> graph = new HashMap<>();
        Set<String> projectPackages = new HashSet<>();

        // First pass: collect all project packages
        for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                String pkgName = pkg.getElementName();

                if (packageFilter == null || pkgName.startsWith(packageFilter)) {
                    if (pkg.getCompilationUnits().length > 0) {
                        projectPackages.add(pkgName);
                        graph.putIfAbsent(pkgName, new HashSet<>());
                    }
                }
            }
        }

        // Second pass: collect dependencies
        for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                String pkgName = pkg.getElementName();

                if (!projectPackages.contains(pkgName)) continue;

                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    for (IImportDeclaration imp : cu.getImports()) {
                        String importPkg = extractPackage(imp.getElementName());

                        // Only track dependencies to other project packages
                        if (projectPackages.contains(importPkg) && !importPkg.equals(pkgName)) {
                            graph.get(pkgName).add(importPkg);
                        }
                    }
                }
            }
        }

        return graph;
    }

    /**
     * Find strongly connected components using Tarjan's algorithm.
     */
    private List<Set<String>> findSCCs(Map<String, Set<String>> graph) {
        List<Set<String>> sccs = new ArrayList<>();
        Map<String, Integer> indices = new HashMap<>();
        Map<String, Integer> lowLinks = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] index = {0};

        for (String node : graph.keySet()) {
            if (!indices.containsKey(node)) {
                strongConnect(node, graph, indices, lowLinks, onStack, stack, sccs, index);
            }
        }

        return sccs;
    }

    /**
     * Tarjan's strongconnect function.
     */
    private void strongConnect(String v, Map<String, Set<String>> graph,
                                Map<String, Integer> indices, Map<String, Integer> lowLinks,
                                Set<String> onStack, Deque<String> stack,
                                List<Set<String>> sccs, int[] index) {
        indices.put(v, index[0]);
        lowLinks.put(v, index[0]);
        index[0]++;
        stack.push(v);
        onStack.add(v);

        // Consider successors
        Set<String> successors = graph.getOrDefault(v, Set.of());
        for (String w : successors) {
            if (!indices.containsKey(w)) {
                // w not yet visited
                strongConnect(w, graph, indices, lowLinks, onStack, stack, sccs, index);
                lowLinks.put(v, Math.min(lowLinks.get(v), lowLinks.get(w)));
            } else if (onStack.contains(w)) {
                // w is in stack, hence in current SCC
                lowLinks.put(v, Math.min(lowLinks.get(v), indices.get(w)));
            }
        }

        // If v is root of SCC
        if (lowLinks.get(v).equals(indices.get(v))) {
            Set<String> scc = new HashSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!w.equals(v));

            sccs.add(scc);
        }
    }

    /**
     * Find an actual cycle path within an SCC for clear reporting.
     */
    private List<String> findCyclePath(Set<String> scc, Map<String, Set<String>> graph) {
        if (scc.isEmpty()) return List.of();

        // Start from any node and find path back to it
        String start = scc.iterator().next();
        List<String> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        if (findPath(start, start, scc, graph, path, visited, true)) {
            path.add(start); // Complete the cycle
            return path;
        }

        // Fallback: just return the SCC nodes
        return new ArrayList<>(scc);
    }

    /**
     * DFS to find path from current to target within SCC.
     */
    private boolean findPath(String current, String target, Set<String> scc,
                              Map<String, Set<String>> graph, List<String> path,
                              Set<String> visited, boolean isStart) {
        if (!isStart && current.equals(target)) {
            return true;
        }

        if (!isStart && visited.contains(current)) {
            return false;
        }

        visited.add(current);
        path.add(current);

        Set<String> successors = graph.getOrDefault(current, Set.of());
        for (String next : successors) {
            if (!scc.contains(next)) continue;
            if (findPath(next, target, scc, graph, path, visited, false)) {
                return true;
            }
        }

        path.remove(path.size() - 1);
        return false;
    }

    private String extractPackage(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot > 0 ? typeName.substring(0, lastDot) : "";
    }
}
