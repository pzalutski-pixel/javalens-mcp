package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.search.SearchMatch;
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
 * Comprehensive type analysis in a single call.
 * Combines type info, members, hierarchy, usage summary, and diagnostics.
 */
public class AnalyzeTypeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeTypeTool.class);

    public AnalyzeTypeTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_type";
    }

    @Override
    public String getDescription() {
        return """
            Comprehensive type analysis in a single call.

            Combines:
            - Type info (name, kind, modifiers, location)
            - All members (methods, fields, constructors)
            - Type hierarchy (superclass, interfaces, subtypes)
            - Usage summary (instantiations, casts, etc.)
            - Diagnostics for the type's file

            Use this instead of multiple calls to get_type_members + get_type_hierarchy + get_type_usage_summary.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("typeName", Map.of(
            "type", "string",
            "description", "Fully qualified or simple type name"
        ));
        properties.put("includeUsages", Map.of(
            "type", "boolean",
            "description", "Include usage analysis (default true)"
        ));
        properties.put("maxUsages", Map.of(
            "type", "integer",
            "description", "Max usages per category (default 10)"
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("typeName"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }

        boolean includeUsages = getBooleanParam(arguments, "includeUsages", true);
        int maxUsages = getIntParam(arguments, "maxUsages", 10);

        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            ICompilationUnit cu = type.getCompilationUnit();
            Map<String, Object> data = new LinkedHashMap<>();

            // Type info
            data.put("type", createTypeInfo(type, service, cu));

            // Members
            data.put("members", createMembersInfo(type, service, cu));

            // Hierarchy
            data.put("hierarchy", createHierarchyInfo(type, service));

            // Usages (optional)
            if (includeUsages) {
                data.put("usages", createUsageInfo(type, service, maxUsages));
            }

            // Diagnostics for this file
            if (cu != null) {
                data.put("diagnostics", collectDiagnostics(cu));
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "analyze_method for detailed method analysis",
                    "find_references for specific symbol usages",
                    "get_call_hierarchy_incoming for method callers"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error analyzing type: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service, ICompilationUnit cu)
            throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());
        info.put("package", type.getPackageFragment().getElementName());

        // Kind
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

        // Location
        if (cu != null && cu.getResource() != null) {
            info.put("file", service.getPathUtils().formatPath(
                cu.getResource().getLocation().toOSString()));
            info.put("line", service.getLineNumber(cu, type.getSourceRange().getOffset()));
        }

        return info;
    }

    private Map<String, Object> createMembersInfo(IType type, IJdtService service, ICompilationUnit cu)
            throws JavaModelException {
        Map<String, Object> members = new LinkedHashMap<>();

        // Constructors
        List<Map<String, Object>> constructors = new ArrayList<>();
        for (IMethod method : type.getMethods()) {
            if (method.isConstructor()) {
                constructors.add(createMethodInfo(method, service, cu));
            }
        }
        members.put("constructors", constructors);

        // Methods (non-constructor)
        List<Map<String, Object>> methods = new ArrayList<>();
        for (IMethod method : type.getMethods()) {
            if (!method.isConstructor()) {
                methods.add(createMethodInfo(method, service, cu));
            }
        }
        members.put("methods", methods);

        // Fields
        List<Map<String, Object>> fields = new ArrayList<>();
        for (IField field : type.getFields()) {
            fields.add(createFieldInfo(field, service, cu));
        }
        members.put("fields", fields);

        // Nested types
        List<Map<String, Object>> nestedTypes = new ArrayList<>();
        for (IType nested : type.getTypes()) {
            Map<String, Object> nestedInfo = new LinkedHashMap<>();
            nestedInfo.put("name", nested.getElementName());
            nestedInfo.put("kind", getTypeKind(nested));
            nestedInfo.put("modifiers", getModifiers(nested.getFlags()));
            nestedTypes.add(nestedInfo);
        }
        members.put("nestedTypes", nestedTypes);

        // Counts
        members.put("constructorCount", constructors.size());
        members.put("methodCount", methods.size());
        members.put("fieldCount", fields.size());
        members.put("nestedTypeCount", nestedTypes.size());

        return members;
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service, ICompilationUnit cu)
            throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());
        info.put("modifiers", getModifiers(method.getFlags()));

        // Signature
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
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        info.put("signature", sig.toString());

        if (!method.isConstructor()) {
            info.put("returnType", Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }

        // Exceptions
        String[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0) {
            List<String> exceptionNames = new ArrayList<>();
            for (String exc : exceptions) {
                exceptionNames.add(Signature.getSimpleName(Signature.toString(exc)));
            }
            info.put("exceptions", exceptionNames);
        }

        info.put("line", service.getLineNumber(cu, method.getSourceRange().getOffset()));
        return info;
    }

    private Map<String, Object> createFieldInfo(IField field, IJdtService service, ICompilationUnit cu)
            throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", field.getElementName());
        info.put("type", Signature.getSimpleName(Signature.toString(field.getTypeSignature())));
        info.put("modifiers", getModifiers(field.getFlags()));

        if (field.isEnumConstant()) {
            info.put("enumConstant", true);
        }

        info.put("line", service.getLineNumber(cu, field.getSourceRange().getOffset()));
        return info;
    }

    private Map<String, Object> createHierarchyInfo(IType type, IJdtService service) {
        Map<String, Object> hierarchy = new LinkedHashMap<>();

        try {
            ITypeHierarchy typeHierarchy = service.getSearchService().getTypeHierarchy(type);

            // Superclass
            IType superclass = typeHierarchy.getSuperclass(type);
            if (superclass != null && !"java.lang.Object".equals(superclass.getFullyQualifiedName())) {
                hierarchy.put("superclass", Map.of(
                    "name", superclass.getElementName(),
                    "qualifiedName", superclass.getFullyQualifiedName()
                ));
            }

            // Interfaces
            IType[] interfaces = typeHierarchy.getSuperInterfaces(type);
            if (interfaces.length > 0) {
                List<Map<String, Object>> interfaceList = new ArrayList<>();
                for (IType iface : interfaces) {
                    interfaceList.add(Map.of(
                        "name", iface.getElementName(),
                        "qualifiedName", iface.getFullyQualifiedName()
                    ));
                }
                hierarchy.put("interfaces", interfaceList);
            }

            // Subtypes
            IType[] subtypes = typeHierarchy.getAllSubtypes(type);
            if (subtypes.length > 0) {
                List<Map<String, Object>> subtypeList = new ArrayList<>();
                for (IType subtype : subtypes) {
                    if (subtypeList.size() >= 20) break; // Limit subtypes
                    subtypeList.add(Map.of(
                        "name", subtype.getElementName(),
                        "qualifiedName", subtype.getFullyQualifiedName()
                    ));
                }
                hierarchy.put("subtypes", subtypeList);
                hierarchy.put("subtypeCount", subtypes.length);
            }

        } catch (Exception e) {
            log.debug("Error getting hierarchy: {}", e.getMessage());
        }

        return hierarchy;
    }

    private Map<String, Object> createUsageInfo(IType type, IJdtService service, int maxUsages) {
        Map<String, Object> usages = new LinkedHashMap<>();
        int total = 0;

        try {
            // Instantiations
            List<SearchMatch> instantiations = service.getSearchService()
                .findTypeInstantiations(type, maxUsages);
            usages.put("instantiations", instantiations.size());
            total += instantiations.size();

            // Casts
            List<SearchMatch> casts = service.getSearchService()
                .findCasts(type, maxUsages);
            usages.put("casts", casts.size());
            total += casts.size();

            // Instanceof
            List<SearchMatch> instanceofs = service.getSearchService()
                .findInstanceofChecks(type, maxUsages);
            usages.put("instanceofChecks", instanceofs.size());
            total += instanceofs.size();

            // Type arguments
            List<SearchMatch> typeArgs = service.getSearchService()
                .findTypeArguments(type, maxUsages);
            usages.put("typeArguments", typeArgs.size());
            total += typeArgs.size();

            usages.put("total", total);

        } catch (Exception e) {
            log.debug("Error getting usages: {}", e.getMessage());
        }

        return usages;
    }

    private Map<String, Object> collectDiagnostics(ICompilationUnit cu) {
        Map<String, Object> result = new LinkedHashMap<>();
        int errorCount = 0;
        int warningCount = 0;

        try {
            CompilationUnit ast = (CompilationUnit) cu.reconcile(
                AST.getJLSLatest(),
                ICompilationUnit.FORCE_PROBLEM_DETECTION,
                null,
                null
            );

            if (ast != null) {
                for (IProblem problem : ast.getProblems()) {
                    if (problem.isError()) errorCount++;
                    else if (problem.isWarning()) warningCount++;
                }
            }
        } catch (JavaModelException e) {
            log.debug("Error collecting diagnostics: {}", e.getMessage());
        }

        result.put("errorCount", errorCount);
        result.put("warningCount", warningCount);
        result.put("hasProblems", errorCount > 0 || warningCount > 0);

        return result;
    }

    private String getTypeKind(IType type) {
        try {
            if (type.isInterface()) return "Interface";
            if (type.isEnum()) return "Enum";
            if (type.isAnnotation()) return "Annotation";
            if (type.isRecord()) return "Record";
            return "Class";
        } catch (Exception e) {
            return "Unknown";
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
        return modifiers;
    }
}
