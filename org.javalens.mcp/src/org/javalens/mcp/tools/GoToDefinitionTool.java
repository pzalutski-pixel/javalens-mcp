package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Navigate to symbol definition.
 * Uses JDT code select and resolution to find the declaration.
 */
public class GoToDefinitionTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GoToDefinitionTool.class);

    public GoToDefinitionTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "go_to_definition";
    }

    @Override
    public String getDescription() {
        return """
            Navigate to symbol definition.

            USAGE: Position cursor on a symbol reference, returns definition location.
            OUTPUT: File path, line, column of the definition.

            IMPORTANT: Uses ZERO-BASED coordinates.
            If editor shows 'Line 14, Column 5', pass line=13, column=4

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
                "description", "Path to source file (absolute or relative to project)"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
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

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        try {
            Path path = Path.of(filePath);

            // Get element at position using code select
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Get the source range for the definition
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", element.getElementName());
            result.put("kind", getElementKind(element));

            // Get location of the declaration
            if (element instanceof ISourceReference sourceRef) {
                ISourceRange range = sourceRef.getSourceRange();
                if (range != null) {
                    ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
                    if (cu != null) {
                        int defLine = service.getLineNumber(cu, range.getOffset());
                        int defColumn = service.getColumnNumber(cu, range.getOffset());

                        // Get file path
                        String defFilePath = getFilePath(element, service);

                        result.put("location", Map.of(
                            "filePath", defFilePath,
                            "line", defLine,
                            "column", defColumn
                        ));
                    }
                }
            }

            // Add containing type info
            if (element instanceof IMethod method && method.getDeclaringType() != null) {
                result.put("containingType", method.getDeclaringType().getFullyQualifiedName());
            } else if (element instanceof IField field && field.getDeclaringType() != null) {
                result.put("containingType", field.getDeclaringType().getFullyQualifiedName());
            } else if (element instanceof IType type) {
                if (type.getPackageFragment() != null) {
                    result.put("package", type.getPackageFragment().getElementName());
                }
            }

            return ToolResponse.success(result, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to see all usages of this symbol",
                    "get_type_hierarchy if this is a type",
                    "get_type_members to see all members of the containing type"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error going to definition: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> {
                if (element instanceof IType type) {
                    try {
                        if (type.isInterface()) yield "Interface";
                        if (type.isEnum()) yield "Enum";
                        if (type.isAnnotation()) yield "Annotation";
                    } catch (JavaModelException e) {
                        // Fall through
                    }
                }
                yield "Class";
            }
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            case IJavaElement.TYPE_PARAMETER -> "TypeParameter";
            default -> "Unknown";
        };
    }

    private String getFilePath(IJavaElement element, IJdtService service) {
        try {
            ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu != null && cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    return service.getPathUtils().formatPath(location.toOSString());
                }
            }
        } catch (Exception e) {
            log.debug("Error getting file path: {}", e.getMessage());
        }
        return "";
    }
}
