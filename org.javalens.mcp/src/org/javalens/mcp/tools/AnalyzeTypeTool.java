package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
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
import org.javalens.core.search.ModelHierarchy;
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

    /** The most subtypes, and potential subtypes, the hierarchy lists. */
    private static final int MAX_SUBTYPES = 20;

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

            USAGE: analyze_type(typeName="com.example.Foo")
            OUTPUT: Type info, members, hierarchy, usage summary, diagnostics

            Combines:
            - Type info (name, kind, modifiers, location)
            - All members (methods, fields, constructors)
            - Type hierarchy (superclass, interfaces, subtypes)
            - Usage summary (instantiations, casts, etc.)
            - Diagnostics for the type's file

            Use this instead of multiple calls to get_type_members + get_type_hierarchy + get_type_usage_summary.

            hierarchy.unresolvedSupertypes, present only when there are any, names
            each supertype the compiler cannot resolve (a missing dependency), as the
            declaration writes it, with the type whose declaration names it: this
            type or one of its supertypes. The hierarchy's potentialMatches are
            places naming the type as a supertype that JDT could not confirm; the
            usage summary's are uses it could not confirm, the first maxUsages of
            each category.

            """ + POTENTIAL_MATCHES + """
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
        info.put("name", ElementNames.typeName(type));
        info.put("qualifiedName", ElementNames.qualifiedName(type));
        info.put("package", ElementNames.packageName(type.getPackageFragment().getElementName()));

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
            nestedInfo.put("name", ElementNames.typeName(nested));
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
            // The hierarchy as the Java model holds it, local and anonymous classes included
            ModelHierarchy typeHierarchy = service.getSearchService().hierarchyOf(type);

            // Superclass
            IType superclass = typeHierarchy.superclass();
            if (superclass != null && !"java.lang.Object".equals(superclass.getFullyQualifiedName())) {
                hierarchy.put("superclass", Map.of(
                    "name", ElementNames.typeName(superclass),
                    "qualifiedName", ElementNames.qualifiedName(superclass)
                ));
            }

            // Interfaces
            List<IType> interfaces = typeHierarchy.superInterfaces();
            if (!interfaces.isEmpty()) {
                List<Map<String, Object>> interfaceList = new ArrayList<>();
                for (IType iface : interfaces) {
                    interfaceList.add(Map.of(
                        "name", ElementNames.typeName(iface),
                        "qualifiedName", ElementNames.qualifiedName(iface)
                    ));
                }
                hierarchy.put("interfaces", interfaceList);
            }

            // Supertypes the compiler cannot resolve, by name, with the type naming each
            List<Map<String, Object>> unresolved = unresolvedSupertypes(typeHierarchy);
            if (unresolved != null) {
                hierarchy.put("unresolvedSupertypes", unresolved);
            }

            // Subtypes, by file and position
            List<IType> subtypes = typeHierarchy.allSubtypes();
            if (!subtypes.isEmpty()) {
                List<Map<String, Object>> subtypeList = new ArrayList<>();
                for (IType subtype : subtypes) {
                    if (subtypeList.size() >= MAX_SUBTYPES) break;
                    subtypeList.add(Map.of(
                        "name", ElementNames.typeName(subtype),
                        "qualifiedName", ElementNames.qualifiedName(subtype)
                    ));
                }
                hierarchy.put("subtypes", subtypeList);
                hierarchy.put("subtypeCount", subtypes.size());
            }
            Map<String, Object> potential = potentialSubtypes(typeHierarchy, MAX_SUBTYPES, service);
            if (potential != null) {
                hierarchy.put("potentialMatches", potential);
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

            // Each category's count is its totalEncountered (pre-clip count), so the aggregate
            // reflects all uses, not just the maxUsages-clipped sample. Annotation usages count
            // only for an annotation type, which cannot be instantiated, cast and so on; this
            // mirrors GetTypeUsageSummaryTool so the two tools surface the same vocabulary.
            Map<String, org.javalens.core.search.SearchService.ReferenceKind> categories = new LinkedHashMap<>();
            categories.put("instantiations", org.javalens.core.search.SearchService.ReferenceKind.INSTANTIATION);
            categories.put("casts", org.javalens.core.search.SearchService.ReferenceKind.CAST);
            categories.put("instanceofChecks", org.javalens.core.search.SearchService.ReferenceKind.INSTANCEOF);
            categories.put("typeArguments", org.javalens.core.search.SearchService.ReferenceKind.TYPE_ARGUMENT);
            if (type.isAnnotation()) {
                categories.put("annotationUsages", org.javalens.core.search.SearchService.ReferenceKind.ANNOTATION);
            }

            // The places each category's search could not confirm, the first maxUsages of each
            List<org.eclipse.jdt.core.search.SearchMatch> potential = new ArrayList<>();
            int potentialTotal = 0;
            for (Map.Entry<String, org.javalens.core.search.SearchService.ReferenceKind> category : categories.entrySet()) {
                org.javalens.core.search.SearchResult result = search.findReferences(type, category.getValue(), maxUsages);
                usages.put(category.getKey(), result.totalEncountered());
                total += result.totalEncountered();
                potential.addAll(result.potentialMatches());
                potentialTotal += result.potentialTotal();
            }

            usages.put("total", total);
            Map<String, Object> potentialMatches = potentialMatches(potential, potentialTotal, service);
            if (potentialMatches != null) {
                usages.put("potentialMatches", potentialMatches);
            }

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
