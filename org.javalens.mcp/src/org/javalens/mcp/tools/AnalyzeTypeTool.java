package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
import org.javalens.core.TypeKindResolver;
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
        return SchemaBuilder.object()
            .required("typeName", "string", "Fully qualified or simple type name")
            .optional("includeUsages", "boolean", "Include usage analysis (default true)")
            .optional("maxUsages", "integer", "Max usages per category (default 10)")
            .build();
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

        info.put("kind", TypeKindResolver.kindOf(type));

        info.put("modifiers", ModifierFormatter.format(type.getFlags()));

        // Type parameters and their bounds (for generic types).
        ITypeParameter[] typeParams = type.getTypeParameters();
        if (typeParams != null && typeParams.length > 0) {
            List<String> names = new ArrayList<>();
            Map<String, List<String>> boundsByName = new LinkedHashMap<>();
            for (ITypeParameter tp : typeParams) {
                names.add(tp.getElementName());
                String[] bounds = tp.getBounds();
                if (bounds != null && bounds.length > 0) {
                    boundsByName.put(tp.getElementName(), List.of(bounds));
                } else {
                    boundsByName.put(tp.getElementName(), List.of());
                }
            }
            info.put("typeParameters", names);
            info.put("typeParameterBounds", boundsByName);
        }

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
            nestedInfo.put("kind", TypeKindResolver.kindOf(nested));
            nestedInfo.put("modifiers", ModifierFormatter.format(nested.getFlags()));
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
        info.put("modifiers", ModifierFormatter.format(method.getFlags()));

        info.put("signature", MethodFormatter.signature(method));
        String returnType = MethodFormatter.returnTypeSimpleName(method);
        if (returnType != null) {
            info.put("returnType", returnType);
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
        info.put("modifiers", ModifierFormatter.format(field.getFlags()));

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
            org.javalens.core.search.SearchService search = service.getSearchService();

            // Instantiations — use totalEncountered (pre-clip count) so the aggregate
            // reflects all uses, not just the maxUsages-clipped sample.
            int instantiations = search.findReferences(
                type, org.javalens.core.search.SearchService.ReferenceKind.INSTANTIATION, maxUsages).totalEncountered();
            usages.put("instantiations", instantiations);
            total += instantiations;

            // Casts
            int casts = search.findReferences(
                type, org.javalens.core.search.SearchService.ReferenceKind.CAST, maxUsages).totalEncountered();
            usages.put("casts", casts);
            total += casts;

            // Instanceof
            int instanceofs = search.findReferences(
                type, org.javalens.core.search.SearchService.ReferenceKind.INSTANCEOF, maxUsages).totalEncountered();
            usages.put("instanceofChecks", instanceofs);
            total += instanceofs;

            // Type arguments
            int typeArgs = search.findReferences(
                type, org.javalens.core.search.SearchService.ReferenceKind.TYPE_ARGUMENT, maxUsages).totalEncountered();
            usages.put("typeArguments", typeArgs);
            total += typeArgs;

            // Annotation usages (only meaningful for annotation types; @interface
            // can't be instantiated/cast/etc., so the four categories above are all
            // empty for it). Mirrors GetTypeUsageSummaryTool's annotation branch so
            // the two analyzer tools surface the same vocabulary.
            if (type.isAnnotation()) {
                int annotations = search.findReferences(
                    type, org.javalens.core.search.SearchService.ReferenceKind.ANNOTATION, maxUsages).totalEncountered();
                usages.put("annotationUsages", annotations);
                total += annotations;
            }

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
            // FORCE_PROBLEM_DETECTION requires the CU to be in working-copy mode.
            boolean wasWorkingCopy = cu.isWorkingCopy();
            if (!wasWorkingCopy) {
                cu.becomeWorkingCopy(null);
            }

            CompilationUnit ast;
            try {
                ast = (CompilationUnit) cu.reconcile(
                    AST.getJLSLatest(),
                    ICompilationUnit.FORCE_PROBLEM_DETECTION,
                    null,
                    null
                );
            } finally {
                if (!wasWorkingCopy) {
                    cu.discardWorkingCopy();
                }
            }

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

}
