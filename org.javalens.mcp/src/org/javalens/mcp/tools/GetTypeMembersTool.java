package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get all members (methods, fields, nested types) of a specific type.
 * Can optionally include inherited members.
 */
public class GetTypeMembersTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetTypeMembersTool.class);

    public GetTypeMembersTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_type_members";
    }

    @Override
    public String getDescription() {
        return """
            Get all members (methods, fields, nested types) of a specific type.

            USAGE: Provide a type name to get all its members
            OUTPUT: Lists of methods, fields, and nested types with their details

            Options:
            - includeInherited: Also include members from superclasses/interfaces
            - memberKind: Filter to "method", "field", or "type" only

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "typeName", Map.of(
                "type", "string",
                "description", "Fully qualified or simple type name"
            ),
            "includeInherited", Map.of(
                "type", "boolean",
                "description", "Include inherited members (default false)"
            ),
            "memberKind", Map.of(
                "type", "string",
                "description", "Filter: 'method', 'field', 'type', or null for all"
            )
        ));
        schema.put("required", List.of("typeName"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }

        boolean includeInherited = getBooleanParam(arguments, "includeInherited", false);
        String memberKind = getStringParam(arguments, "memberKind");

        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", createTypeInfo(type, service));

            List<Map<String, Object>> methods = new ArrayList<>();
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> nestedTypes = new ArrayList<>();

            // Collect direct members
            if (memberKind == null || "method".equals(memberKind)) {
                collectMethods(type, methods, service, null);
            }
            if (memberKind == null || "field".equals(memberKind)) {
                collectFields(type, fields, service, null);
            }
            if (memberKind == null || "type".equals(memberKind)) {
                collectNestedTypes(type, nestedTypes, service, null);
            }

            // Collect inherited members if requested
            if (includeInherited) {
                ITypeHierarchy hierarchy = service.getSearchService().getTypeHierarchy(type);
                IType[] supertypes = hierarchy.getAllSuperclasses(type);

                for (IType supertype : supertypes) {
                    String declaredIn = supertype.getFullyQualifiedName();
                    if (memberKind == null || "method".equals(memberKind)) {
                        collectMethods(supertype, methods, service, declaredIn);
                    }
                    if (memberKind == null || "field".equals(memberKind)) {
                        collectFields(supertype, fields, service, declaredIn);
                    }
                }

                // Include interface default methods
                IType[] interfaces = hierarchy.getAllSuperInterfaces(type);
                for (IType iface : interfaces) {
                    String declaredIn = iface.getFullyQualifiedName();
                    if (memberKind == null || "method".equals(memberKind)) {
                        collectMethods(iface, methods, service, declaredIn);
                    }
                }
            }

            if (memberKind == null || "method".equals(memberKind)) {
                data.put("methods", methods);
            }
            if (memberKind == null || "field".equals(memberKind)) {
                data.put("fields", fields);
            }
            if (memberKind == null || "type".equals(memberKind)) {
                data.put("nestedTypes", nestedTypes);
            }

            int totalMembers = methods.size() + fields.size() + nestedTypes.size();
            data.put("totalMembers", totalMembers);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalMembers)
                .returnedCount(totalMembers)
                .suggestedNextTools(List.of(
                    "find_references to find usages of a member",
                    "go_to_definition to navigate to member source",
                    "get_type_hierarchy to see inheritance chain"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting type members: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void collectMethods(IType type, List<Map<String, Object>> methods,
                                 IJdtService service, String declaredIn) {
        try {
            for (IMethod method : type.getMethods()) {
                Map<String, Object> info = createMethodInfo(method, service, declaredIn);
                if (info != null) {
                    methods.add(info);
                }
            }
        } catch (JavaModelException e) {
            log.debug("Error collecting methods: {}", e.getMessage());
        }
    }

    private void collectFields(IType type, List<Map<String, Object>> fields,
                                IJdtService service, String declaredIn) {
        try {
            for (IField field : type.getFields()) {
                Map<String, Object> info = createFieldInfo(field, service, declaredIn);
                if (info != null) {
                    fields.add(info);
                }
            }
        } catch (JavaModelException e) {
            log.debug("Error collecting fields: {}", e.getMessage());
        }
    }

    private void collectNestedTypes(IType type, List<Map<String, Object>> nestedTypes,
                                     IJdtService service, String declaredIn) {
        try {
            for (IType nested : type.getTypes()) {
                Map<String, Object> info = createNestedTypeInfo(nested, service, declaredIn);
                if (info != null) {
                    nestedTypes.add(info);
                }
            }
        } catch (JavaModelException e) {
            log.debug("Error collecting nested types: {}", e.getMessage());
        }
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());

        try {
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
        } catch (JavaModelException e) {
            info.put("kind", "Class");
        }

        try {
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu != null && cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }
        } catch (Exception e) {
            // External type
        }

        return info;
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service, String declaredIn) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", method.getElementName());

            if (method.isConstructor()) {
                info.put("kind", "Constructor");
            } else {
                info.put("kind", "Method");
            }

            int flags = method.getFlags();
            info.put("modifiers", getModifiers(flags));

            // Build signature
            StringBuilder sig = new StringBuilder();
            sig.append(method.getElementName()).append("(");
            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
                if (i < paramNames.length) {
                    sig.append(" ").append(paramNames[i]);
                }
            }
            sig.append(")");

            if (!method.isConstructor()) {
                String returnType = Signature.getSimpleName(Signature.toString(method.getReturnType()));
                sig.append(": ").append(returnType);
            }

            info.put("signature", sig.toString());

            if (declaredIn != null) {
                info.put("declaredIn", declaredIn);
            }

            // Get line if source available
            ICompilationUnit cu = method.getCompilationUnit();
            if (cu != null) {
                int offset = method.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
            }

            return info;

        } catch (JavaModelException e) {
            log.debug("Error creating method info: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> createFieldInfo(IField field, IJdtService service, String declaredIn) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", field.getElementName());

            int flags = field.getFlags();
            if (field.isEnumConstant()) {
                info.put("kind", "EnumConstant");
            } else if (Flags.isStatic(flags) && Flags.isFinal(flags)) {
                info.put("kind", "Constant");
            } else {
                info.put("kind", "Field");
            }

            info.put("modifiers", getModifiers(flags));
            info.put("type", Signature.getSimpleName(Signature.toString(field.getTypeSignature())));

            if (declaredIn != null) {
                info.put("declaredIn", declaredIn);
            }

            ICompilationUnit cu = field.getCompilationUnit();
            if (cu != null) {
                int offset = field.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
            }

            return info;

        } catch (JavaModelException e) {
            log.debug("Error creating field info: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> createNestedTypeInfo(IType type, IJdtService service, String declaredIn) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", type.getElementName());
            info.put("qualifiedName", type.getFullyQualifiedName());

            if (type.isInterface()) {
                info.put("kind", "Interface");
            } else if (type.isEnum()) {
                info.put("kind", "Enum");
            } else if (type.isAnnotation()) {
                info.put("kind", "Annotation");
            } else {
                info.put("kind", "Class");
            }

            int flags = type.getFlags();
            info.put("modifiers", getModifiers(flags));

            if (declaredIn != null) {
                info.put("declaredIn", declaredIn);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            if (cu != null) {
                int offset = type.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
            }

            return info;

        } catch (JavaModelException e) {
            log.debug("Error creating nested type info: {}", e.getMessage());
            return null;
        }
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
