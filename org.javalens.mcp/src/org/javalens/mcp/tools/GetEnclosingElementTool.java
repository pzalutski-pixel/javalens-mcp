package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
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
 * Get the enclosing element (method, class, package) at a position.
 */
public class GetEnclosingElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetEnclosingElementTool.class);

    public GetEnclosingElementTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_enclosing_element";
    }

    @Override
    public String getDescription() {
        return """
            Get the enclosing element at a position.

            USAGE: Position anywhere in code
            OUTPUT: Enclosing method, type, and package info

            IMPORTANT: Uses ZERO-BASED coordinates.

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
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            // If no element at exact position, try getting the compilation unit
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // If no element found, use getElementAt with offset
            if (element == null) {
                int offset = service.getOffset(cu, line, column);
                element = cu.getElementAt(offset);
            }

            Map<String, Object> data = new LinkedHashMap<>();

            // Current element info
            if (element != null) {
                data.put("element", createElementInfo(element, service));
            }

            // Find enclosing method
            IMethod enclosingMethod = null;
            if (element != null) {
                if (element instanceof IMethod m) {
                    enclosingMethod = m;
                } else {
                    enclosingMethod = (IMethod) element.getAncestor(IJavaElement.METHOD);
                }
            }

            if (enclosingMethod != null) {
                data.put("enclosingMethod", createMethodInfo(enclosingMethod, service));
            }

            // Find enclosing type
            IType enclosingType = null;
            if (element != null) {
                if (element instanceof IType t) {
                    enclosingType = t;
                } else {
                    enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
                }
            }

            if (enclosingType != null) {
                data.put("enclosingType", createTypeInfo(enclosingType, service));

                // Check for outer class if this is nested
                IType outerType = enclosingType.getDeclaringType();
                if (outerType != null) {
                    data.put("outerType", createTypeInfo(outerType, service));
                }
            }

            // Find package
            IPackageFragment pkg = (IPackageFragment) cu.getAncestor(IJavaElement.PACKAGE_FRAGMENT);
            if (pkg != null) {
                String pkgName = pkg.getElementName();
                data.put("enclosingPackage", pkgName.isEmpty() ? "(default package)" : pkgName);
            }

            // File info
            if (cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    data.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_type_members for type members",
                    "get_document_symbols for file structure"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting enclosing element: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createElementInfo(IJavaElement element, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", element.getElementName());
        info.put("kind", getElementKind(element));
        return info;
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());
        info.put("isConstructor", method.isConstructor());

        // Build signature
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
        String[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
        }
        sig.append(")");
        if (!method.isConstructor()) {
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        info.put("signature", sig.toString());

        info.put("modifiers", getModifiers(method.getFlags()));

        // Line number
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu != null && method.getSourceRange() != null) {
            info.put("line", service.getLineNumber(cu, method.getSourceRange().getOffset()));
        }

        return info;
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());

        if (type.isInterface()) {
            info.put("kind", "Interface");
        } else if (type.isEnum()) {
            info.put("kind", "Enum");
        } else if (type.isAnnotation()) {
            info.put("kind", "Annotation");
        } else if (type.isRecord()) {
            info.put("kind", "Record");
        } else {
            info.put("kind", "Class");
        }

        info.put("modifiers", getModifiers(type.getFlags()));

        ICompilationUnit cu = type.getCompilationUnit();
        if (cu != null && type.getSourceRange() != null) {
            info.put("line", service.getLineNumber(cu, type.getSourceRange().getOffset()));
        }

        return info;
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "LocalVariable";
            case IJavaElement.INITIALIZER -> "Initializer";
            case IJavaElement.PACKAGE_FRAGMENT -> "Package";
            case IJavaElement.COMPILATION_UNIT -> "CompilationUnit";
            default -> "Unknown";
        };
    }

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        return modifiers;
    }
}
