package org.javalens.core.graph;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link ProjectGraph} in a single batch-AST pass with resolved
 * bindings over all source compilation units of a project.
 */
final class GraphBuilder {

    private GraphBuilder() {
    }

    static ProjectGraph build(IJavaProject project) throws JavaModelException {
        Map<String, ProjectGraph.GraphNode> nodes = new LinkedHashMap<>();
        Set<ProjectGraph.GraphEdge> edges = new LinkedHashSet<>();
        Map<String, Set<String>> overrides = new HashMap<>();
        Set<String> mains = new HashSet<>();

        ICompilationUnit[] units = collectSourceUnits(project);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(project);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.createASTs(units, new String[0], new ASTRequestor() {
            @Override
            public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
                ast.accept(new GraphCollectorVisitor(source, ast, nodes, edges, overrides, mains));
            }
        }, null);

        return new ProjectGraph(nodes, new ArrayList<>(edges), overrides, mains);
    }

    private static ICompilationUnit[] collectSourceUnits(IJavaProject project) throws JavaModelException {
        List<ICompilationUnit> units = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                        units.add(cu);
                    }
                }
            }
        }
        return units.toArray(new ICompilationUnit[0]);
    }
}
