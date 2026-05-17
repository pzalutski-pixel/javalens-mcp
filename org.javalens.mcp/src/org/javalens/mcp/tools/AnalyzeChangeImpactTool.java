package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
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

                    List<SearchMatch> matches = service.getSearchService().findAllReferences(target, 500);

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
}
