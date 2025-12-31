package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
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
 * Find all callers of a method (incoming calls).
 *
 * Uses SearchService to find method references, then extracts the enclosing method
 * for each reference to determine who is calling the target method.
 */
public class GetCallHierarchyIncomingTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetCallHierarchyIncomingTool.class);

    public GetCallHierarchyIncomingTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_call_hierarchy_incoming";
    }

    @Override
    public String getDescription() {
        return """
            Find all callers of a method (incoming calls).

            USAGE: Position cursor on a method name
            OUTPUT: List of methods that call this method

            IMPORTANT: Uses ZERO-BASED coordinates.

            Useful for understanding who depends on a method before changing it.

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
                "description", "Max callers to return (default 50)"
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
        int maxResults = getIntParam(arguments, "maxResults", 50);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        maxResults = Math.min(Math.max(maxResults, 1), 500);

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Must be a method
            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position", "Symbol at position is not a method");
            }

            // Get method metadata
            String methodName = method.getElementName();
            IType declaringType = method.getDeclaringType();
            String declaringClass = declaringType != null ? declaringType.getFullyQualifiedName() : "Unknown";
            String signature = buildMethodSignature(method);

            // Use SearchService to find all references to this method
            List<SearchMatch> matches = service.getSearchService()
                .findAllReferences(method, maxResults * 2); // Request more since some may not have enclosing methods

            // Extract caller information from each match
            List<Map<String, Object>> callers = new ArrayList<>();
            for (SearchMatch match : matches) {
                if (callers.size() >= maxResults) break;

                Map<String, Object> callerInfo = extractCallerInfo(match, service);
                if (callerInfo != null) {
                    callers.add(callerInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", methodName);
            data.put("declaringClass", declaringClass);
            data.put("signature", signature);
            data.put("totalCallers", callers.size());
            data.put("callers", callers);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(callers.size())
                .returnedCount(callers.size())
                .truncated(callers.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "get_call_hierarchy_outgoing to see what this method calls",
                    "go_to_definition to navigate to a caller"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding callers: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> extractCallerInfo(SearchMatch match, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();

            // File path
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Get the enclosing method (the caller)
            Object element = match.getElement();
            if (element instanceof IJavaElement javaElement) {
                ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (cu != null) {
                    // Line and column of the call site
                    int callLine = service.getLineNumber(cu, match.getOffset());
                    int callColumn = service.getColumnNumber(cu, match.getOffset());
                    info.put("line", callLine);
                    info.put("column", callColumn);

                    // Get context line
                    String context = service.getContextLine(cu, match.getOffset());
                    if (!context.isEmpty()) {
                        info.put("context", context);
                    }
                }

                // Find the enclosing method (the caller)
                IMethod callerMethod = (IMethod) javaElement.getAncestor(IJavaElement.METHOD);
                if (callerMethod != null) {
                    info.put("callerMethod", callerMethod.getElementName());
                    info.put("callerSignature", buildMethodSignature(callerMethod));

                    IType callerType = callerMethod.getDeclaringType();
                    if (callerType != null) {
                        info.put("callerClass", callerType.getFullyQualifiedName());
                    }
                } else {
                    // Called from initializer or field declaration
                    IType enclosingType = (IType) javaElement.getAncestor(IJavaElement.TYPE);
                    if (enclosingType != null) {
                        info.put("callerMethod", "<initializer>");
                        info.put("callerClass", enclosingType.getFullyQualifiedName());
                    }
                }

                return info;
            }

            return null;

        } catch (Exception e) {
            log.debug("Error extracting caller info: {}", e.getMessage());
            return null;
        }
    }

    private String buildMethodSignature(IMethod method) {
        try {
            StringBuilder sig = new StringBuilder();
            sig.append(method.getElementName()).append("(");

            String[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(Signature.getSignatureSimpleName(paramTypes[i]));
            }

            sig.append(")");
            return sig.toString();
        } catch (Exception e) {
            return method.getElementName() + "(...)";
        }
    }
}
