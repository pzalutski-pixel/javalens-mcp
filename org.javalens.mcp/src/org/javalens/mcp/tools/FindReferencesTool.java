package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
import org.javalens.core.search.SearchResult;
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
 * Find all references to a symbol across the project.
 * Uses JDT SearchEngine for fast indexed search.
 */
public class FindReferencesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindReferencesTool.class);

    public FindReferencesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_references";
    }

    @Override
    public String getDescription() {
        return """
            Find all references to a symbol across the project.

            USAGE: Position on symbol, find all usages
            OUTPUT: List of reference locations with context

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("maxResults", "integer", "Max references to return (default 100)")
            .build();
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
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }
        // Honor maxResults=0 literally (return zero results). Upper bound is a safety
        // cap to prevent unbounded memory use; values above 1000 are clipped silently.
        maxResults = Math.min(maxResults, 1000);

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Use SearchService for indexed reference search
            SearchResult result = service.getSearchService()
                .findAllReferences(element, maxResults);

            // Convert matches to reference info
            List<Map<String, Object>> references = new ArrayList<>();
            for (SearchMatch match : result.matches()) {
                Map<String, Object> refInfo = createReferenceInfo(match, service);
                if (refInfo != null) {
                    references.add(refInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("symbolKind", getElementKind(element));

            // Add containing type info
            if (element instanceof IMethod method && method.getDeclaringType() != null) {
                data.put("containingType", method.getDeclaringType().getElementName());
            } else if (element instanceof IField field && field.getDeclaringType() != null) {
                data.put("containingType", field.getDeclaringType().getElementName());
            }

            data.put("totalCount", result.totalEncountered());
            data.put("locations", references);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(result.totalEncountered())
                .returnedCount(references.size())
                .truncated(result.truncated())
                .suggestedNextTools(List.of(
                    "go_to_definition to see the symbol definition",
                    "get_type_hierarchy for type symbols"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding references: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createReferenceInfo(SearchMatch match, IJdtService service) {
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
                    int refLine = service.getLineNumber(cu, match.getOffset());
                    int refColumn = service.getColumnNumber(cu, match.getOffset());
                    info.put("line", refLine);
                    info.put("column", refColumn);

                    // Get context line
                    String context = service.getContextLine(cu, match.getOffset());
                    if (!context.isEmpty()) {
                        info.put("context", context);
                    }
                }
            }

            // Reference kind based on match accuracy
            String refKind = getReferenceKind(match);
            info.put("referenceKind", refKind);

            return info;

        } catch (Exception e) {
            log.debug("Error creating reference info: {}", e.getMessage());
            return null;
        }
    }

    private String getReferenceKind(SearchMatch match) {
        if (match.isInsideDocComment()) {
            return "JAVADOC";
        }

        // Classify by the SearchMatch subclass, which encodes the reference role.
        // match.getElement() is the *enclosing* IMember (the method or field that
        // contains the reference) — using it would misclassify a type reference
        // inside a field declaration as FIELD_ACCESS, or a field read inside a
        // method body as METHOD_INVOCATION.
        if (match instanceof org.eclipse.jdt.core.search.MethodReferenceMatch) {
            return "METHOD_INVOCATION";
        }
        if (match instanceof org.eclipse.jdt.core.search.FieldReferenceMatch) {
            return "FIELD_ACCESS";
        }
        if (match instanceof org.eclipse.jdt.core.search.TypeReferenceMatch) {
            return "TYPE_REFERENCE";
        }
        if (match instanceof org.eclipse.jdt.core.search.LocalVariableReferenceMatch) {
            return "VARIABLE_ACCESS";
        }
        if (match instanceof org.eclipse.jdt.core.search.PackageReferenceMatch) {
            return "PACKAGE_REFERENCE";
        }
        if (match instanceof org.eclipse.jdt.core.search.TypeParameterReferenceMatch) {
            return "TYPE_PARAMETER_REFERENCE";
        }
        return "REFERENCE";
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> element instanceof IType type
                ? TypeKindResolver.kindOf(type)
                : "class";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            default -> "Unknown";
        };
    }
}
