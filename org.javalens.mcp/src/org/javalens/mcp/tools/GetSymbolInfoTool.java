package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
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
 * Get detailed information about any symbol at a position.
 */
public class GetSymbolInfoTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetSymbolInfoTool.class);

    public GetSymbolInfoTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_symbol_info";
    }

    @Override
    public String getDescription() {
        return """
            Get detailed information about any symbol at a position.

            USAGE: Position on any symbol (type, method, field, variable)
            OUTPUT: Comprehensive info including kind, modifiers, signature, location

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

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            Map<String, Object> data = createElementInfo(element, service);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to find all usages",
                    "go_to_definition to navigate to definition",
                    "get_type_hierarchy for type inheritance"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting symbol info: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createElementInfo(IJavaElement element, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", element.getElementName());
        info.put("kind", getElementKind(element));

        // Add type-specific info
        if (element instanceof IType type) {
            addTypeInfo(info, type, service);
        } else if (element instanceof IMethod method) {
            addMethodInfo(info, method, service);
        } else if (element instanceof IField field) {
            addFieldInfo(info, field, service);
        } else if (element instanceof ILocalVariable local) {
            addLocalVariableInfo(info, local);
        } else if (element instanceof ITypeParameter typeParam) {
            addTypeParameterInfo(info, typeParam);
        }

        // Common member info
        if (element instanceof IMember member) {
            int flags = member.getFlags();
            info.put("modifiers", getModifiers(flags));

            IType declaringType = member.getDeclaringType();
            if (declaringType != null) {
                info.put("declaringType", declaringType.getFullyQualifiedName());
            }
        }

        // Source location
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu != null) {
            if (cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            if (element instanceof IMember member && member.getSourceRange() != null) {
                int offset = member.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
                info.put("column", service.getColumnNumber(cu, offset));
            }
        }

        return info;
    }

    private void addTypeInfo(Map<String, Object> info, IType type, IJdtService service) throws JavaModelException {
        info.put("qualifiedName", type.getFullyQualifiedName());

        if (type.isInterface()) {
            info.put("typeKind", "interface");
        } else if (type.isEnum()) {
            info.put("typeKind", "enum");
        } else if (type.isAnnotation()) {
            info.put("typeKind", "annotation");
        } else if (type.isRecord()) {
            info.put("typeKind", "record");
        } else {
            info.put("typeKind", "class");
        }

        String superclass = type.getSuperclassName();
        if (superclass != null) {
            info.put("superclass", superclass);
        }

        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces.length > 0) {
            info.put("interfaces", List.of(interfaces));
        }

        ITypeParameter[] typeParams = type.getTypeParameters();
        if (typeParams.length > 0) {
            List<String> params = new ArrayList<>();
            for (ITypeParameter tp : typeParams) {
                params.add(tp.getElementName());
            }
            info.put("typeParameters", params);
        }
    }

    private void addMethodInfo(Map<String, Object> info, IMethod method, IJdtService service) throws JavaModelException {
        info.put("isConstructor", method.isConstructor());

        if (!method.isConstructor()) {
            info.put("returnType", Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }

        // Parameters
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        List<Map<String, String>> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.length; i++) {
            Map<String, String> param = new LinkedHashMap<>();
            param.put("type", Signature.getSimpleName(Signature.toString(paramTypes[i])));
            if (i < paramNames.length) {
                param.put("name", paramNames[i]);
            }
            params.add(param);
        }
        if (!params.isEmpty()) {
            info.put("parameters", params);
        }

        // Exceptions
        String[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0) {
            List<String> exList = new ArrayList<>();
            for (String ex : exceptions) {
                exList.add(Signature.getSimpleName(Signature.toString(ex)));
            }
            info.put("exceptions", exList);
        }

        // Build signature
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
        }
        sig.append(")");
        if (!method.isConstructor()) {
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        info.put("signature", sig.toString());
    }

    private void addFieldInfo(Map<String, Object> info, IField field, IJdtService service) throws JavaModelException {
        info.put("type", Signature.getSimpleName(Signature.toString(field.getTypeSignature())));
        info.put("isEnumConstant", field.isEnumConstant());

        Object constant = field.getConstant();
        if (constant != null) {
            info.put("constantValue", constant.toString());
        }
    }

    private void addLocalVariableInfo(Map<String, Object> info, ILocalVariable local) throws JavaModelException {
        info.put("type", Signature.getSimpleName(Signature.toString(local.getTypeSignature())));
        info.put("isParameter", local.isParameter());
    }

    private void addTypeParameterInfo(Map<String, Object> info, ITypeParameter typeParam) throws JavaModelException {
        String[] bounds = typeParam.getBounds();
        if (bounds.length > 0) {
            info.put("bounds", List.of(bounds));
        }
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "LocalVariable";
            case IJavaElement.TYPE_PARAMETER -> "TypeParameter";
            case IJavaElement.PACKAGE_FRAGMENT -> "Package";
            case IJavaElement.COMPILATION_UNIT -> "CompilationUnit";
            case IJavaElement.INITIALIZER -> "Initializer";
            case IJavaElement.IMPORT_DECLARATION -> "Import";
            case IJavaElement.PACKAGE_DECLARATION -> "PackageDeclaration";
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
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags)) modifiers.add("native");
        if (Flags.isDefaultMethod(flags)) modifiers.add("default");
        return modifiers;
    }
}
