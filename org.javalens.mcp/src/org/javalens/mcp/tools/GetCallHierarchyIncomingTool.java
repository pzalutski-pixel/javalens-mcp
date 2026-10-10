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
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
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

    /** The largest {@code maxResults} accepted. */
    private static final int MAX_RESULTS = 500;

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

            The callers of a method of a class file, such as a JDK method, are those
            in the project's source files. maxResults outside 0 to 500 is
            INVALID_PARAMETER.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("maxResults", "integer", "Max callers to return, 0 to 500 (default 50)")
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
        int maxResults = getIntParam(arguments, "maxResults", 50);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }
        // maxResults=0 is honored literally (zero results); the documented maximum bounds the list
        if (maxResults < 0 || maxResults > MAX_RESULTS) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be between 0 and " + MAX_RESULTS + "; got: " + maxResults);
        }

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
            String declaringClass = declaringType != null ? ElementNames.qualifiedName(declaringType) : "Unknown";
            String signature = buildMethodSignature(method);

            // Every reference to this method: extraction skips matches it cannot place,
            // so it must see all of them before the count and the page are taken.
            SearchResult result = service.getSearchService()
                .findAllReferences(method, SearchService.ALL_MATCHES);

            // Extract caller information from each match. Count total successful
            // extractions separately from the displayed list so truncated is accurate:
            // comparing callers.size() to maxResults misreports the exact-equal case.
            List<Map<String, Object>> callers = new ArrayList<>();
            int totalCallers = 0;
            for (SearchMatch match : result.matches()) {
                Map<String, Object> callerInfo = extractCallerInfo(match, service);
                if (callerInfo != null) {
                    totalCallers++;
                    if (callers.size() < maxResults) {
                        callers.add(callerInfo);
                    }
                }
            }
            boolean truncated = totalCallers > maxResults;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", methodName);
            data.put("declaringClass", declaringClass);
            data.put("signature", signature);
            data.put("totalCallers", totalCallers);
            data.put("callers", callers);
            List<SearchMatch> potential = result.potentialMatches();
            Map<String, Object> potentialMatches = potentialMatches(
                potential.subList(0, Math.min(maxResults, potential.size())), result.potentialTotal(), service);
            if (potentialMatches != null) {
                data.put("potentialMatches", potentialMatches);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalCallers)
                .returnedCount(callers.size())
                .truncated(truncated)
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

                // The caller is the member holding the call that has a name: a call in a
                // lambda belongs to the method, initializer or field the lambda is written in.
                IJavaElement caller = ElementNames.namedMember(javaElement);
                if (caller instanceof IMethod callerMethod) {
                    info.put("callerMethod", callerMethod.getElementName());
                    info.put("callerSignature", buildMethodSignature(callerMethod));

                    IType callerType = callerMethod.getDeclaringType();
                    if (callerType != null) {
                        info.put("callerClass", ElementNames.qualifiedName(callerType));
                    }
                } else {
                    // Called from initializer or field declaration
                    IType enclosingType = caller == null ? null : ElementNames.enclosingType(caller);
                    if (enclosingType != null) {
                        info.put("callerMethod", ElementNames.INITIALIZER);
                        info.put("callerClass", ElementNames.qualifiedName(enclosingType));
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
