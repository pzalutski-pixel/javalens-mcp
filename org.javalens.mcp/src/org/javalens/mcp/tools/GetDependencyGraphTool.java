package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract package/type dependencies as a graph.
 *
 * Analyzes imports, inheritance, fields, and method signatures to build
 * a dependency graph with nodes and edges.
 */
public class GetDependencyGraphTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetDependencyGraphTool.class);

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
        "int", "long", "short", "byte", "char", "boolean", "float", "double", "void");

    public GetDependencyGraphTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_dependency_graph";
    }

    @Override
    public String getDescription() {
        return """
            Get package/type dependencies.

            USAGE: get_dependency_graph(scope="type", name="com.example.OrderService")
            USAGE: get_dependency_graph(scope="package", name="com.example.service")
            OUTPUT: Dependency graph with nodes and edges

            Each node has a name and a kind. A node that denotes a type is named
            by the type's fully qualified name with '$' between nested types
            (com.example.Outer$Inner), however the source names it, so each
            type is one node. A node that denotes a type, or a member of one (a
            static import), also has the package of that type as JDT resolves
            it ("(default package)" for the default package).
            A node that denotes a package (every node at package scope, and an
            on-demand package import) has no package; neither has a name that
            does not resolve.

            At package scope the graph covers the package and its subpackages:
            each import is an edge from the package of the file declaring it to
            the package it imports, and every edge's source and target is a node,
            as is the requested package.

            Dependency types tracked:
            - import: Direct imports
            - extends: Superclass inheritance
            - implements: Interface implementation
            - field: Field type dependencies
            - parameter: Method parameter types
            - return: Method return types

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("scope", "string", "Scope: 'type' or 'package'")
            .required("name", "string", "Type name (fully qualified) or package name")
            .optional("depth", "integer", "How deep to follow dependencies (default: 1)")
            .optional("includeExternal", "boolean", "Include JDK/library dependencies (default: false)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String scope = getStringParam(arguments, "scope");
        String name = getStringParam(arguments, "name");
        int depth = getIntParam(arguments, "depth", 1);
        boolean includeExternal = getBooleanParam(arguments, "includeExternal", false);

        if (scope == null || scope.isBlank()) {
            return ToolResponse.invalidParameter("scope", "Required (type or package)");
        }
        if (name == null || name.isBlank()) {
            return ToolResponse.invalidParameter("name", "Required");
        }

        try {
            Graph graph = new Graph();

            if ("type".equals(scope)) {
                IType type = service.findType(name);
                if (type == null) {
                    return ToolResponse.symbolNotFound(name);
                }
                collectTypeDependencies(type, service, graph, includeExternal, depth, new HashSet<>());
            } else if ("package".equals(scope)) {
                collectPackageDependencies(name, service, graph, includeExternal);
            } else {
                return ToolResponse.invalidParameter("scope", "Must be 'type' or 'package'");
            }

            // Convert to output format
            List<Map<String, Object>> nodeList = new ArrayList<>();
            for (String node : graph.nodes) {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("name", node);
                nodeInfo.put("kind", graph.kinds.getOrDefault(node, "unknown"));
                Target target = graph.targets.get(node);
                if (target != null && target.packageName() != null) {
                    nodeInfo.put("package", ElementNames.packageName(target.packageName()));
                }
                nodeList.add(nodeInfo);
            }

            List<Map<String, Object>> edgeList = new ArrayList<>();
            int internalCount = 0;
            int externalCount = 0;

            for (Map.Entry<String, Map<String, Integer>> fromEntry : graph.edges.entrySet()) {
                String from = fromEntry.getKey();
                for (Map.Entry<String, Integer> toEntry : fromEntry.getValue().entrySet()) {
                    String to = toEntry.getKey();
                    int count = toEntry.getValue();

                    Map<String, Object> edgeInfo = new LinkedHashMap<>();
                    edgeInfo.put("from", from);
                    edgeInfo.put("to", to);
                    edgeInfo.put("count", count);
                    edgeList.add(edgeInfo);

                    Target target = graph.targets.get(to);
                    if (isExternalPackage(target != null ? target.home() : extractPackage(to))) {
                        externalCount++;
                    } else {
                        internalCount++;
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scope", scope);
            data.put("root", name);
            data.put("nodes", nodeList);
            data.put("edges", edgeList);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalNodes", nodeList.size());
            summary.put("totalEdges", edgeList.size());
            summary.put("internalDependencies", internalCount);
            summary.put("externalDependencies", externalCount);
            data.put("summary", summary);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(nodeList.size())
                .returnedCount(nodeList.size())
                .suggestedNextTools(List.of(
                    "find_circular_dependencies to detect cycles",
                    "get_type_hierarchy for inheritance analysis"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error building dependency graph: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * What a node denotes: a package, whose name is the node's own; or a type, or a member of
     * one, in the package {@code packageName} as JDT resolves it, null when the name does not
     * resolve.
     */
    private record Target(String name, String packageName, boolean isPackage) {

        static Target ofPackage(String name) {
            return new Target(name, null, true);
        }

        /**
         * The package a dependency on this target is a dependency on, which tells a JDK
         * dependency from a project one: the package itself, the type's package, or for a name
         * that does not resolve the name without its last segment.
         */
        String home() {
            if (isPackage) {
                return name;
            }
            return packageName != null ? packageName : extractPackage(name);
        }
    }

    /** The nodes, edges, node kinds and node targets of the graph being built. */
    private static final class Graph {
        final Set<String> nodes = new HashSet<>();
        final Map<String, Map<String, Integer>> edges = new HashMap<>(); // from -> {to -> count}
        final Map<String, String> kinds = new HashMap<>();
        final Map<String, Target> targets = new HashMap<>();

        /**
         * An edge from {@code from} to {@code target}, kept when {@code includeExternal} or when
         * the target is not in a JDK package; the target's first kind and target are kept.
         * Answers whether the edge is kept.
         */
        boolean depend(String from, Target target, String kind, boolean includeExternal) {
            if (target == null || (!includeExternal && isExternalPackage(target.home()))) {
                return false;
            }
            edges.computeIfAbsent(from, k -> new HashMap<>()).merge(target.name(), 1, Integer::sum);
            nodes.add(target.name());
            kinds.putIfAbsent(target.name(), kind);
            targets.putIfAbsent(target.name(), target);
            return true;
        }

        /** The package {@code name} as a node. */
        void addPackage(String name) {
            nodes.add(name);
            kinds.putIfAbsent(name, "package");
            targets.putIfAbsent(name, Target.ofPackage(name));
        }
    }

    /**
     * Collect dependencies for a type.
     */
    private void collectTypeDependencies(IType type, IJdtService service, Graph graph,
                                          boolean includeExternal, int depth, Set<String> visited) throws Exception {
        String typeName = type.getFullyQualifiedName();
        if (visited.contains(typeName) || depth < 0) return;
        visited.add(typeName);

        graph.nodes.add(typeName);
        graph.kinds.put(typeName, TypeKindResolver.kindOf(type));
        graph.targets.put(typeName, new Target(typeName, type.getPackageFragment().getElementName(), false));

        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) return;

        // 1. Import dependencies
        Map<Integer, IBinding> importBindings = importBindings(cu);
        for (IImportDeclaration imp : cu.getImports()) {
            if (Flags.isModule(imp.getFlags())) {
                // import module M; (JEP 511) names a module, not a package or
                // type. The JDT model surfaces it as an on-demand import whose
                // element name is "M.*", so the on-demand strip below would turn
                // it into a bogus dependency on a package named after the module.
                // It carries no package/type dependency — skip it.
                continue;
            }
            String importName = imp.getElementName();
            if (imp.isOnDemand()) {
                importName = importName.replace(".*", "");
            }
            graph.depend(typeName, importTarget(importName, importBindings.get(imp.getSourceRange().getOffset())),
                "import", includeExternal);
        }

        // 2. Superclass
        String superName = type.getSuperclassName();
        if (superName != null && !superName.equals("Object")) {
            graph.depend(typeName, resolveTypeName(type, superName), "class", includeExternal);
        }

        // 3. Interfaces
        for (String ifaceName : type.getSuperInterfaceNames()) {
            graph.depend(typeName, resolveTypeName(type, ifaceName), "interface", includeExternal);
        }

        // 4. Field types
        for (IField field : type.getFields()) {
            try {
                String fieldType = Signature.toString(field.getTypeSignature());
                graph.depend(typeName, resolveTypeName(type, fieldType), "type", includeExternal);
            } catch (Exception e) {
                // Skip unresolvable types
            }
        }

        // 5. Method signatures
        for (IMethod method : type.getMethods()) {
            try {
                // Return type
                String returnType = Signature.toString(method.getReturnType());
                if (!returnType.equals("void")) {
                    graph.depend(typeName, resolveTypeName(type, returnType), "type", includeExternal);
                }

                // Parameters
                for (String paramSig : method.getParameterTypes()) {
                    graph.depend(typeName, resolveTypeName(type, Signature.toString(paramSig)), "type",
                        includeExternal);
                }
            } catch (Exception e) {
                // Skip unresolvable types
            }
        }

        // Recurse for deeper analysis
        if (depth > 1) {
            for (String node : new HashSet<>(graph.nodes)) {
                if (!visited.contains(node)) {
                    try {
                        IType depType = service.findType(node);
                        if (depType != null && depType.getCompilationUnit() != null) {
                            collectTypeDependencies(depType, service, graph, includeExternal, depth - 1, visited);
                        }
                    } catch (Exception e) {
                        // Skip unresolvable types
                    }
                }
            }
        }
    }

    /**
     * Collect dependencies at package level: the imports of the package and of its subpackages,
     * each an edge from the package of the file declaring it, which is then a node.
     */
    private void collectPackageDependencies(String packageName, IJdtService service, Graph graph,
                                             boolean includeExternal) throws Exception {

        graph.addPackage(packageName);

        for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                String pkgName = pkg.getElementName();

                if (!pkgName.equals(packageName) && !pkgName.startsWith(packageName + ".")) {
                    continue;
                }

                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    Map<Integer, IBinding> importBindings = importBindings(cu);
                    for (IImportDeclaration imp : cu.getImports()) {
                        String importPkg = getImportedPackage(imp, importBindings.get(imp.getSourceRange().getOffset()));
                        if (importPkg.isEmpty()) continue;

                        if (importPkg.equals(pkgName)) continue;
                        if (graph.depend(pkgName, Target.ofPackage(importPkg), "package", includeExternal)) {
                            graph.addPackage(pkgName);
                        }
                    }
                }
            }
        }
    }

    private static String extractPackage(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot > 0 ? typeName.substring(0, lastDot) : "";
    }

    /**
     * The binding of each import declaration in {@code unit}, keyed by the declaration's start
     * offset; null for a declaration that does not resolve.
     */
    private static Map<Integer, IBinding> importBindings(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());
        Map<Integer, IBinding> bindings = new HashMap<>();
        for (Object declaration : ast.imports()) {
            ImportDeclaration imp = (ImportDeclaration) declaration;
            bindings.put(imp.getStartPosition(), imp.resolveBinding());
        }
        return bindings;
    }

    /**
     * What the import node {@code name} denotes, from the import's {@code binding}: the package
     * an on-demand package import names; the type a type import or a type-import-on-demand names,
     * in its package, named as every type node is ({@link ElementNames#qualifiedName(ITypeBinding)},
     * '$' between nested types); the static member a static import names, as the import writes
     * it, in its declaring type's package. A name that does not resolve keeps no package.
     */
    private static Target importTarget(String name, IBinding binding) {
        if (binding instanceof IPackageBinding) {
            return Target.ofPackage(name);
        }
        if (binding instanceof ITypeBinding type) {
            return new Target(ElementNames.qualifiedName(type), packageOf(type), false);
        }
        return new Target(name, packageOf(binding), false);
    }

    /**
     * The package of the type {@code binding} denotes, or of the type declaring the method or
     * field it denotes; null for any other binding, or none.
     */
    private static String packageOf(IBinding binding) {
        ITypeBinding type = switch (binding) {
            case ITypeBinding typeBinding -> typeBinding;
            case IMethodBinding method -> method.getDeclaringClass();
            case IVariableBinding variable -> variable.getDeclaringClass();
            case null, default -> null;
        };
        IPackageBinding pkg = type == null ? null : type.getPackage();
        return pkg == null ? null : pkg.getName();
    }

    /**
     * Returns the imported PACKAGE name for any import form: the package an on-demand package
     * import names, or the package of the type, or of the static member's declaring type, the
     * import names, as its binding resolves it. An import that does not resolve is read from its
     * text: {@code isStatic()} and {@code isOnDemand()} say how many trailing components to
     * strip.
     */
    private String getImportedPackage(IImportDeclaration imp, IBinding binding) {
        try {
            if (Flags.isModule(imp.getFlags())) {
                // import module M; (JEP 511) names a module, not a package. Its
                // element name is "M.*", which the on-demand strip would read as
                // a package named after the module. It contributes no package.
                return "";
            }
            if (binding instanceof IPackageBinding pkg) {
                return pkg.getName();
            }
            String resolved = packageOf(binding);
            if (resolved != null) {
                return resolved;
            }
            String name = imp.getElementName();
            boolean onDemand = imp.isOnDemand();
            boolean isStatic = Flags.isStatic(imp.getFlags());

            if (onDemand && name.endsWith(".*")) {
                name = name.substring(0, name.length() - 2);
            }
            if (isStatic && !onDemand) {
                // Regular static import: "pkg.Type.member" — drop member.
                int lastDot = name.lastIndexOf('.');
                if (lastDot > 0) name = name.substring(0, lastDot);
            }
            if (onDemand && !isStatic) {
                // Regular on-demand: name is already the package after .* strip.
                return name;
            }
            // All other cases: name is now "pkg.Type" — drop Type to get pkg.
            int lastDot = name.lastIndexOf('.');
            return lastDot > 0 ? name.substring(0, lastDot) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isExternalPackage(String packageName) {
        return packageName.startsWith("java.") ||
               packageName.startsWith("javax.") ||
               packageName.startsWith("sun.") ||
               packageName.startsWith("com.sun.") ||
               packageName.startsWith("jdk.") ||
               packageName.startsWith("org.w3c.") ||
               packageName.startsWith("org.xml.");
    }

    /**
     * What a type name written in a signature of {@code context} denotes: the type it resolves
     * to through {@link IType#resolveType} (which honours imports, the enclosing package,
     * implicit {@code java.lang} and a qualified name's segments), named fully qualified with
     * '$' between nested types, as the type's own node is named, and carrying its package; or
     * null when it is not a project or library type dependency. Primitives and {@code void}
     * carry no dependency; a simple name that does not resolve contributes none rather than a
     * fabricated same-package type, and a qualified name that does not resolve is kept as
     * written, its package unknown.
     */
    private Target resolveTypeName(IType context, String typeName) {
        // Remove array brackets and generics
        String baseName = typeName.replaceAll("\\[\\]", "").replaceAll("<.*>", "");

        if (PRIMITIVE_TYPES.contains(baseName)) {
            return null;
        }

        try {
            String[][] resolved = context.resolveType(baseName);
            if (resolved != null && resolved.length > 0) {
                String pkg = resolved[0][0] == null ? "" : resolved[0][0];
                // The type-qualified name, dots between nested types (Outer.Inner).
                String nested = resolved[0][1].replace('.', '$');
                return new Target(pkg.isEmpty() ? nested : pkg + "." + nested, pkg, false);
            }
        } catch (Exception e) {
            // Fall through: unresolved names contribute no dependency.
        }

        // Unresolved — do not invent a context-package dependency.
        return baseName.contains(".") ? new Target(baseName, null, false) : null;
    }

}
