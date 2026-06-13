package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.graph.ProjectGraph;
import org.javalens.core.graph.ProjectGraph.GraphNode;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Analyze the blast radius of changing a symbol.
 * Returns all files, call sites, and enclosing methods affected.
 * Supports multi-level depth for transitive impact analysis.
 */
public class AnalyzeChangeImpactTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeChangeImpactTool.class);

    public AnalyzeChangeImpactTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_change_impact";
    }

    @Override
    public String getDescription() {
        return """
            Analyze the blast radius of changing a symbol.

            USAGE: analyze_change_impact(filePath="path/to/File.java", line=10, column=5)
            OUTPUT: All files and call sites affected, grouped by file

            Options:
            - depth: How many levels of callers to follow (default 1, max 3)
              depth=1: direct references only
              depth=2: references + callers of those references
              depth=3: three levels deep
            - transitive: full reverse closure over the project call graph
              (default false). No depth ceiling; follows calls, instantiations,
              field accesses, and override declarations (callers through an
              interface or superclass count). Returns affectedMethods +
              affectedFiles instead of callSites. Supports project methods,
              fields, and types.
            - maxResults: cap on affectedMethods in transitive mode (default 200)

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "File containing the symbol")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("depth", "integer", "Levels of transitive callers to follow (default 1, max 3)")
            .optional("transitive", "boolean", "Full reverse closure over the project graph, no depth ceiling (default false)")
            .optional("maxResults", "integer", "Cap on affectedMethods in transitive mode (default 200)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse paramCheck = requireParam(arguments, "filePath");
        if (paramCheck != null) return paramCheck;

        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);
        int depth = Math.min(getIntParam(arguments, "depth", 1), 3);

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at " + filePathStr + ":" + line + ":" + column);
            }

            if (getBooleanParam(arguments, "transitive", false)) {
                return transitiveImpact(service, element, getIntParam(arguments, "maxResults", 200));
            }

            // Collect all references at each depth level
            List<Map<String, Object>> allCallSites = new ArrayList<>();
            Set<String> affectedFileSet = new LinkedHashMap<String, Object>().keySet();
            Map<String, Integer> fileReferenceCounts = new LinkedHashMap<>();

            Set<String> visited = new HashSet<>();
            List<IJavaElement> currentLevel = List.of(element);

            for (int d = 0; d < depth && !currentLevel.isEmpty(); d++) {
                List<IJavaElement> nextLevel = new ArrayList<>();

                for (IJavaElement target : currentLevel) {
                    String key = target.getHandleIdentifier();
                    if (visited.contains(key)) continue;
                    visited.add(key);

                    List<SearchMatch> matches = service.getSearchService().findAllReferences(target, 500).matches();

                    for (SearchMatch match : matches) {
                        Map<String, Object> callSite = formatMatch(match, service);
                        callSite.put("depth", d + 1);

                        // Extract enclosing method
                        IJavaElement matchElement = (IJavaElement) match.getElement();
                        if (matchElement != null) {
                            IJavaElement enclosing = matchElement.getAncestor(IJavaElement.METHOD);
                            if (enclosing instanceof IMethod enclosingMethod) {
                                callSite.put("enclosingMethod", enclosingMethod.getElementName());
                                IType declaringType = enclosingMethod.getDeclaringType();
                                if (declaringType != null) {
                                    callSite.put("enclosingType", declaringType.getFullyQualifiedName());
                                }
                                // Add enclosing method to next level for deeper analysis
                                if (d + 1 < depth) {
                                    nextLevel.add(enclosingMethod);
                                }
                            }
                        }

                        allCallSites.add(callSite);

                        // Track affected files
                        String file = (String) callSite.get("filePath");
                        if (file != null) {
                            fileReferenceCounts.merge(file, 1, Integer::sum);
                        }
                    }
                }

                currentLevel = nextLevel;
            }

            // Build affected files list
            List<Map<String, Object>> affectedFiles = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : fileReferenceCounts.entrySet()) {
                Map<String, Object> fileEntry = new LinkedHashMap<>();
                fileEntry.put("filePath", entry.getKey());
                fileEntry.put("referenceCount", entry.getValue());
                affectedFiles.add(fileEntry);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("symbolType", element.getClass().getSimpleName().replace("Sourced", "").replace("Impl", ""));
            data.put("depth", depth);
            data.put("directReferences", allCallSites.stream().filter(c -> (int) c.get("depth") == 1).count());
            data.put("totalReferences", allCallSites.size());
            data.put("affectedFiles", affectedFiles);
            data.put("callSites", allCallSites);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(allCallSites.size())
                .returnedCount(allCallSites.size())
                .suggestedNextTools(List.of(
                    "rename_symbol for safe renaming across all affected files",
                    "find_references for simple reference list without grouping"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Full blast radius via the project graph's reverse closure: every method
     * from which the symbol may be invoked or accessed, across override
     * declarations, with no depth ceiling.
     */
    private ToolResponse transitiveImpact(IJdtService service, IJavaElement element, int maxResults)
            throws Exception {
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults", "must be >= 0");
        }

        ProjectGraph graph = service.getProjectGraphService().getGraph();
        String key = graph.keyOf(element);
        if (key == null || graph.node(key) == null) {
            return ToolResponse.invalidParameter("transitive",
                "transitive analysis supports project methods, fields, and types; "
                    + "no graph node for '" + element.getElementName() + "'");
        }

        // Type-aware: a type's blast radius includes callers of its members,
        // not just direct type-node references (issue #32).
        List<String> affectedMethods = graph.transitiveCallersOfSymbol(key).stream().sorted().toList();

        Map<String, Integer> fileCounts = new LinkedHashMap<>();
        for (String methodKey : affectedMethods) {
            GraphNode node = graph.node(methodKey);
            if (node != null) {
                fileCounts.merge(service.getPathUtils().formatPath(node.filePath()), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> affectedFiles = fileCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> {
                Map<String, Object> file = new LinkedHashMap<String, Object>();
                file.put("filePath", e.getKey());
                file.put("methodCount", e.getValue());
                return file;
            })
            .toList();

        int total = affectedMethods.size();
        boolean truncated = total > maxResults;
        List<String> returned = truncated ? affectedMethods.subList(0, maxResults) : affectedMethods;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", element.getElementName());
        data.put("symbolType", element.getClass().getSimpleName().replace("Sourced", "").replace("Impl", ""));
        data.put("transitive", true);
        data.put("totalAffectedMethods", total);
        data.put("affectedMethods", returned);
        data.put("affectedFiles", affectedFiles);

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(total)
            .returnedCount(returned.size())
            .truncated(truncated)
            .suggestedNextTools(List.of(
                "find_affected_tests for the tests covering this symbol",
                "find_references for the direct call sites with locations"))
            .build());
    }
}
