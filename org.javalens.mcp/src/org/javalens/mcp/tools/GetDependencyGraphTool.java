package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.IJdtService;
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "scope", Map.of(
                "type", "string",
                "description", "Scope: 'type' or 'package'"
            ),
            "name", Map.of(
                "type", "string",
                "description", "Type name (fully qualified) or package name"
            ),
            "depth", Map.of(
                "type", "integer",
                "description", "How deep to follow dependencies (default: 1)"
            ),
            "includeExternal", Map.of(
                "type", "boolean",
                "description", "Include JDK/library dependencies (default: false)"
            )
        ));
        schema.put("required", List.of("scope", "name"));
        return schema;
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
            Set<String> nodes = new HashSet<>();
            Map<String, Map<String, Integer>> edges = new HashMap<>(); // from -> {to -> count}
            Map<String, String> nodeKinds = new HashMap<>(); // node -> kind

            if ("type".equals(scope)) {
                IType type = service.findType(name);
                if (type == null) {
                    return ToolResponse.symbolNotFound(name);
                }
                collectTypeDependencies(type, service, nodes, edges, nodeKinds,
                    includeExternal, depth, new HashSet<>());
            } else if ("package".equals(scope)) {
                collectPackageDependencies(name, service, nodes, edges, nodeKinds, includeExternal);
            } else {
                return ToolResponse.invalidParameter("scope", "Must be 'type' or 'package'");
            }

            // Convert to output format
            List<Map<String, Object>> nodeList = new ArrayList<>();
            for (String node : nodes) {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("name", node);
                nodeInfo.put("kind", nodeKinds.getOrDefault(node, "unknown"));
                nodeInfo.put("package", extractPackage(node));
                nodeList.add(nodeInfo);
            }

            List<Map<String, Object>> edgeList = new ArrayList<>();
            int internalCount = 0;
            int externalCount = 0;

            for (Map.Entry<String, Map<String, Integer>> fromEntry : edges.entrySet()) {
                String from = fromEntry.getKey();
                for (Map.Entry<String, Integer> toEntry : fromEntry.getValue().entrySet()) {
                    String to = toEntry.getKey();
                    int count = toEntry.getValue();

                    Map<String, Object> edgeInfo = new LinkedHashMap<>();
                    edgeInfo.put("from", from);
                    edgeInfo.put("to", to);
                    edgeInfo.put("count", count);
                    edgeList.add(edgeInfo);

                    if (isExternalPackage(extractPackage(to))) {
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
     * Collect dependencies for a type.
     */
    private void collectTypeDependencies(IType type, IJdtService service,
                                          Set<String> nodes, Map<String, Map<String, Integer>> edges,
                                          Map<String, String> nodeKinds, boolean includeExternal,
                                          int depth, Set<String> visited) throws Exception {
        String typeName = type.getFullyQualifiedName();
        if (visited.contains(typeName) || depth < 0) return;
        visited.add(typeName);

        nodes.add(typeName);
        nodeKinds.put(typeName, getTypeKind(type));

        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) return;

        // 1. Import dependencies
        for (IImportDeclaration imp : cu.getImports()) {
            String importName = imp.getElementName();
            if (imp.isOnDemand()) {
                importName = importName.replace(".*", "");
            }

            if (!includeExternal && isExternalPackage(extractPackage(importName))) {
                continue;
            }

            addEdge(edges, typeName, importName);
            nodes.add(importName);
            nodeKinds.putIfAbsent(importName, "import");
        }

        // 2. Superclass
        String superName = type.getSuperclassName();
        if (superName != null && !superName.equals("Object")) {
            String resolvedSuper = resolveTypeName(type, superName);
            if (includeExternal || !isExternalPackage(extractPackage(resolvedSuper))) {
                addEdge(edges, typeName, resolvedSuper);
                nodes.add(resolvedSuper);
                nodeKinds.putIfAbsent(resolvedSuper, "class");
            }
        }

        // 3. Interfaces
        for (String ifaceName : type.getSuperInterfaceNames()) {
            String resolved = resolveTypeName(type, ifaceName);
            if (includeExternal || !isExternalPackage(extractPackage(resolved))) {
                addEdge(edges, typeName, resolved);
                nodes.add(resolved);
                nodeKinds.putIfAbsent(resolved, "interface");
            }
        }

        // 4. Field types
        for (IField field : type.getFields()) {
            try {
                String fieldTypeSig = field.getTypeSignature();
                String fieldType = Signature.toString(fieldTypeSig);
                String resolved = resolveTypeName(type, fieldType);

                if (includeExternal || !isExternalPackage(extractPackage(resolved))) {
                    addEdge(edges, typeName, resolved);
                    nodes.add(resolved);
                    nodeKinds.putIfAbsent(resolved, "type");
                }
            } catch (Exception e) {
                // Skip unresolvable types
            }
        }

        // 5. Method signatures
        for (IMethod method : type.getMethods()) {
            try {
                // Return type
                String returnSig = method.getReturnType();
                String returnType = Signature.toString(returnSig);
                if (!returnType.equals("void")) {
                    String resolved = resolveTypeName(type, returnType);
                    if (includeExternal || !isExternalPackage(extractPackage(resolved))) {
                        addEdge(edges, typeName, resolved);
                        nodes.add(resolved);
                        nodeKinds.putIfAbsent(resolved, "type");
                    }
                }

                // Parameters
                for (String paramSig : method.getParameterTypes()) {
                    String paramType = Signature.toString(paramSig);
                    String resolved = resolveTypeName(type, paramType);
                    if (includeExternal || !isExternalPackage(extractPackage(resolved))) {
                        addEdge(edges, typeName, resolved);
                        nodes.add(resolved);
                        nodeKinds.putIfAbsent(resolved, "type");
                    }
                }
            } catch (Exception e) {
                // Skip unresolvable types
            }
        }

        // Recurse for deeper analysis
        if (depth > 1) {
            for (String node : new HashSet<>(nodes)) {
                if (!visited.contains(node)) {
                    try {
                        IType depType = service.findType(node);
                        if (depType != null && depType.getCompilationUnit() != null) {
                            collectTypeDependencies(depType, service, nodes, edges, nodeKinds,
                                includeExternal, depth - 1, visited);
                        }
                    } catch (Exception e) {
                        // Skip unresolvable types
                    }
                }
            }
        }
    }

    /**
     * Collect dependencies at package level.
     */
    private void collectPackageDependencies(String packageName, IJdtService service,
                                             Set<String> nodes, Map<String, Map<String, Integer>> edges,
                                             Map<String, String> nodeKinds, boolean includeExternal)
            throws Exception {

        nodes.add(packageName);
        nodeKinds.put(packageName, "package");

        for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                String pkgName = pkg.getElementName();

                if (!pkgName.equals(packageName) && !pkgName.startsWith(packageName + ".")) {
                    continue;
                }

                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    for (IImportDeclaration imp : cu.getImports()) {
                        String importPkg = extractPackage(imp.getElementName());

                        if (importPkg.equals(pkgName)) continue;
                        if (!includeExternal && isExternalPackage(importPkg)) continue;

                        addEdge(edges, pkgName, importPkg);
                        nodes.add(importPkg);
                        nodeKinds.putIfAbsent(importPkg, "package");
                    }
                }
            }
        }
    }

    private void addEdge(Map<String, Map<String, Integer>> edges, String from, String to) {
        edges.computeIfAbsent(from, k -> new HashMap<>())
             .merge(to, 1, Integer::sum);
    }

    private String extractPackage(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot > 0 ? typeName.substring(0, lastDot) : "";
    }

    private boolean isExternalPackage(String packageName) {
        return packageName.startsWith("java.") ||
               packageName.startsWith("javax.") ||
               packageName.startsWith("sun.") ||
               packageName.startsWith("com.sun.") ||
               packageName.startsWith("jdk.") ||
               packageName.startsWith("org.w3c.") ||
               packageName.startsWith("org.xml.");
    }

    private String resolveTypeName(IType context, String simpleName) {
        // Remove array brackets and generics
        String baseName = simpleName.replaceAll("\\[\\]", "").replaceAll("<.*>", "");

        // If already qualified, return as-is
        if (baseName.contains(".")) return baseName;

        // Try to resolve from imports
        try {
            ICompilationUnit cu = context.getCompilationUnit();
            if (cu != null) {
                for (IImportDeclaration imp : cu.getImports()) {
                    String importName = imp.getElementName();
                    if (importName.endsWith("." + baseName)) {
                        return importName;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to return simple name
        }

        // Return with context package
        String pkg = context.getPackageFragment().getElementName();
        return pkg.isEmpty() ? baseName : pkg + "." + baseName;
    }

    private String getTypeKind(IType type) throws Exception {
        if (type.isInterface()) return "interface";
        if (type.isEnum()) return "enum";
        if (type.isAnnotation()) return "annotation";
        if (type.isRecord()) return "record";
        return "class";
    }
}
