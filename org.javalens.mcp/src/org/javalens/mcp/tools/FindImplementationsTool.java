package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
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
 * Find implementations of an interface or extensions of a class.
 * Uses ITypeHierarchy.getAllSubtypes so transitive implementors via sub-interface
 * chains or multi-level extension are included.
 */
public class FindImplementationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindImplementationsTool.class);

    public FindImplementationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_implementations";
    }

    @Override
    public String getDescription() {
        return """
            Find implementations of an interface or extensions of a class.

            USAGE: Position on a type (interface or class), find all implementors/subclasses
            OUTPUT: List of implementing/extending types with locations

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
            .optional("maxResults", "integer", "Max implementations to return (default 100)")
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
        // Honor maxResults=0 literally; upper bound is a safety cap.
        maxResults = Math.min(maxResults, 1000);

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Must be a type or method
            IType targetType = null;
            IMethod targetMethod = null;

            if (element instanceof IType type) {
                targetType = type;
            } else if (element instanceof IMethod method) {
                targetMethod = method;
                targetType = method.getDeclaringType();
            } else {
                // Try to get the type from the element's ancestor
                targetType = (IType) element.getAncestor(IJavaElement.TYPE);
                if (targetType == null) {
                    return ToolResponse.invalidParameter("position",
                        "Symbol at position is not a type or method");
                }
            }

            // Use type hierarchy for full transitive implementor/subtype walk. The previous
            // IMPLEMENTORS-based search returned only direct implementors, silently dropping
            // subtypes reached via sub-interface chains or multi-level extension.
            IType[] subtypes = service.getSearchService().getAllSubtypes(targetType);

            // Count eligible candidates separately from the displayed list so truncated
            // reports accurately. Comparing implementations.size() to maxResults would
            // fire a false-positive when actual eligible == maxResults exactly.
            List<Map<String, Object>> implementations = new ArrayList<>();
            int eligibleCount = 0;
            for (IType subtype : subtypes) {
                if (targetMethod != null) {
                    IMethod overrider = findMatchingMethod(subtype, targetMethod);
                    if (overrider == null) continue;
                    eligibleCount++;
                    if (implementations.size() < maxResults) {
                        Map<String, Object> implInfo = createMethodImplementationInfo(subtype, overrider, service);
                        if (implInfo != null) implementations.add(implInfo);
                    }
                } else {
                    eligibleCount++;
                    if (implementations.size() < maxResults) {
                        Map<String, Object> implInfo = createTypeImplementationInfo(subtype, service);
                        if (implInfo != null) implementations.add(implInfo);
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", targetType.getElementName());

            try {
                data.put("isInterface", targetType.isInterface());
            } catch (JavaModelException e) {
                // Ignore
            }

            if (targetMethod != null) {
                data.put("method", targetMethod.getElementName());
            }

            data.put("totalImplementations", eligibleCount);
            data.put("implementations", implementations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(eligibleCount)
                .returnedCount(implementations.size())
                .truncated(eligibleCount > maxResults)
                .suggestedNextTools(List.of(
                    "get_type_hierarchy for full inheritance chain",
                    "find_references to see all usages",
                    "get_type_members to see members of an implementation"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding implementations: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeImplementationInfo(IType type, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", type.getElementName());
            info.put("qualifiedName", type.getFullyQualifiedName());
            info.put("kind", TypeKindResolver.kindOf(type));
            populateLocation(type, info, service, type.getNameRange());
            return info;
        } catch (Exception e) {
            log.debug("Error creating type implementation info: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> createMethodImplementationInfo(IType type, IMethod method, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", type.getElementName());
            info.put("qualifiedName", type.getFullyQualifiedName());
            info.put("kind", TypeKindResolver.kindOf(type));
            info.put("method", method.getElementName());
            populateLocation(method, info, service, method.getNameRange());
            return info;
        } catch (Exception e) {
            log.debug("Error creating method implementation info: {}", e.getMessage());
            return null;
        }
    }

    private void populateLocation(IJavaElement element, Map<String, Object> info,
                                   IJdtService service, ISourceRange nameRange) throws JavaModelException {
        IResource resource = element.getResource();
        if (resource != null) {
            IPath location = resource.getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }
        }
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu != null && nameRange != null && nameRange.getOffset() >= 0) {
            int line = service.getLineNumber(cu, nameRange.getOffset());
            int column = service.getColumnNumber(cu, nameRange.getOffset());
            info.put("line", line);
            info.put("column", column);
        }
    }

    private IMethod findMatchingMethod(IType type, IMethod target) throws JavaModelException {
        String targetName = target.getElementName();
        String[] targetParamTypes = target.getParameterTypes();
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(targetName)) continue;
            String[] mParamTypes = m.getParameterTypes();
            if (mParamTypes.length != targetParamTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < targetParamTypes.length; i++) {
                String a = Signature.getSimpleName(Signature.toString(targetParamTypes[i]));
                String b = Signature.getSimpleName(Signature.toString(mParamTypes[i]));
                if (!a.equals(b)) { match = false; break; }
            }
            if (match) return m;
        }
        return null;
    }
}
