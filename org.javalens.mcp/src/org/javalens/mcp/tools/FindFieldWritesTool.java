package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find all write accesses (mutations) to a field.
 * Uses JDT SearchEngine with WRITE_ACCESSES filter for data flow analysis.
 *
 * AI-centric: Helps understand "what modifies this state?" - critical for
 * refactoring, debugging, and impact analysis.
 */
public class FindFieldWritesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindFieldWritesTool.class);

    public FindFieldWritesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_field_writes";
    }

    @Override
    public String getDescription() {
        return """
            Find all write accesses (mutations) to a field.

            USAGE: Position cursor on a field declaration or reference
            OUTPUT: List of locations where the field is modified

            IMPORTANT: Uses ZERO-BASED coordinates.

            Unlike find_references which returns all usages, this returns only
            locations where the field value is changed (assignments, increments, etc).
            Useful for data flow analysis and understanding state mutations.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max write locations to return (default 100)"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        maxResults = Math.min(Math.max(maxResults, 1), 1000);

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Verify it's a field
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "Symbol at position is not a field (found: " + getElementKind(element) + ")");
            }

            // Use SearchService for indexed write access search
            List<SearchMatch> matches = service.getSearchService()
                .findWriteAccesses(field, maxResults);

            // Convert matches to write location info
            List<Map<String, Object>> writeLocations = new ArrayList<>();
            for (SearchMatch match : matches) {
                Map<String, Object> writeInfo = createWriteInfo(match, service);
                if (writeInfo != null) {
                    writeLocations.add(writeInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("field", field.getElementName());

            if (field.getDeclaringType() != null) {
                data.put("declaringType", field.getDeclaringType().getElementName());
            }

            try {
                data.put("fieldType", field.getTypeSignature());
            } catch (Exception e) {
                // Ignore if can't get type
            }

            data.put("totalWriteLocations", writeLocations.size());
            data.put("writeLocations", writeLocations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(writeLocations.size())
                .returnedCount(writeLocations.size())
                .truncated(writeLocations.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_references to see all usages (reads and writes)",
                    "get_call_hierarchy_incoming to find callers of methods that modify this field"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding field writes: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createWriteInfo(SearchMatch match, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();

            // File path
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Get ICompilationUnit for line/column calculation
            Object element = match.getElement();
            if (element instanceof IJavaElement javaElement) {
                ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (cu != null) {
                    int writeLine = service.getLineNumber(cu, match.getOffset());
                    int writeColumn = service.getColumnNumber(cu, match.getOffset());
                    info.put("line", writeLine);
                    info.put("column", writeColumn);

                    // Get context line (shows the actual write statement)
                    String context = service.getContextLine(cu, match.getOffset());
                    if (!context.isEmpty()) {
                        info.put("context", context);
                    }
                }
            }

            info.put("accessType", "WRITE");

            return info;

        } catch (Exception e) {
            log.debug("Error creating write info: {}", e.getMessage());
            return null;
        }
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            default -> "Unknown";
        };
    }
}
