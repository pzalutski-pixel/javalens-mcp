package org.javalens.core.graph;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.javalens.core.graph.ProjectGraph.EdgeKind;
import org.javalens.core.graph.ProjectGraph.GraphEdge;
import org.javalens.core.graph.ProjectGraph.GraphNode;
import org.javalens.core.graph.ProjectGraph.NodeKind;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AST visitor that records graph nodes and edges for one compilation unit.
 *
 * <p>Anonymous and local types have no stable qualified name and are skipped
 * as nodes; edges found inside their bodies (and inside lambdas) attribute to
 * the nearest enclosing named method or type, which over-approximates
 * execution but keeps reachability sound.
 */
final class GraphCollectorVisitor extends ASTVisitor {

    private final CompilationUnit ast;
    private final String filePath;
    private final Map<String, GraphNode> nodes;
    private final Set<GraphEdge> edges;
    private final Map<String, Set<String>> overrides;
    private final Set<String> mains;

    GraphCollectorVisitor(ICompilationUnit source, CompilationUnit ast,
                          Map<String, GraphNode> nodes, Set<GraphEdge> edges,
                          Map<String, Set<String>> overrides, Set<String> mains) {
        this.ast = ast;
        this.filePath = source.getResource() != null && source.getResource().getLocation() != null
            ? source.getResource().getLocation().toOSString()
            : source.getPath().toOSString();
        this.nodes = nodes;
        this.edges = edges;
        this.overrides = overrides;
        this.mains = mains;
    }

    // ========== Node registration ==========

    @Override
    public boolean visit(TypeDeclaration node) {
        registerType(node);
        return true;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        registerType(node);
        return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        registerType(node);
        return true;
    }

    private void registerType(AbstractTypeDeclaration node) {
        ITypeBinding binding = node.resolveBinding();
        String key = typeKey(binding);
        if (key == null) {
            return;
        }
        nodes.put(key, new GraphNode(key, NodeKind.TYPE, null, binding.getName(),
            binding.getModifiers(), filePath, lineOf(node.getName())));
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        IMethodBinding binding = node.resolveBinding();
        String key = methodKey(binding);
        if (key == null) {
            return true;
        }
        String ownerKey = typeKey(binding.getDeclaringClass());
        nodes.put(key, new GraphNode(key, NodeKind.METHOD, ownerKey, node.getName().getIdentifier(),
            binding.getModifiers(), filePath, lineOf(node.getName())));
        recordOverrides(key, binding);
        if (isMainMethod(binding)) {
            mains.add(key);
        }
        return true;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        for (Object fragment : node.fragments()) {
            VariableDeclarationFragment vdf = (VariableDeclarationFragment) fragment;
            IVariableBinding binding = vdf.resolveBinding();
            String key = fieldKey(binding);
            if (key == null) {
                continue;
            }
            String ownerKey = typeKey(binding.getDeclaringClass());
            nodes.put(key, new GraphNode(key, NodeKind.FIELD, ownerKey, binding.getName(),
                binding.getModifiers(), filePath, lineOf(vdf.getName())));
        }
        return true;
    }

    private void recordOverrides(String key, IMethodBinding binding) {
        // Constructors never override (JDT's overrides() still matches them).
        if (binding.isConstructor()) {
            return;
        }
        ITypeBinding declaring = binding.getDeclaringClass();
        if (declaring == null) {
            return;
        }
        Deque<ITypeBinding> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pushSupertypes(declaring, work, seen);
        while (!work.isEmpty()) {
            ITypeBinding superType = work.pop();
            for (IMethodBinding candidate : superType.getDeclaredMethods()) {
                if (binding.overrides(candidate) && candidate.getDeclaringClass() != null
                    && candidate.getDeclaringClass().isFromSource()) {
                    String overriddenKey = methodKey(candidate);
                    if (overriddenKey != null) {
                        overrides.computeIfAbsent(key, k -> new HashSet<>()).add(overriddenKey);
                    }
                }
            }
            pushSupertypes(superType, work, seen);
        }
    }

    private static void pushSupertypes(ITypeBinding type, Deque<ITypeBinding> work, Set<String> seen) {
        ITypeBinding superclass = type.getSuperclass();
        if (superclass != null && seen.add(superclass.getKey())) {
            work.push(superclass);
        }
        for (ITypeBinding itf : type.getInterfaces()) {
            if (seen.add(itf.getKey())) {
                work.push(itf);
            }
        }
    }

    private static boolean isMainMethod(IMethodBinding binding) {
        if (!"main".equals(binding.getName())) {
            return false;
        }
        int mods = binding.getModifiers();
        if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isPublic(mods)) {
            return false;
        }
        ITypeBinding[] params = binding.getParameterTypes();
        return "void".equals(binding.getReturnType().getName())
            && params.length == 1 && "String[]".equals(params[0].getName());
    }

    // ========== Call and creation edges ==========

    @Override
    public boolean visit(MethodInvocation node) {
        addCallEdge(node.resolveMethodBinding(), node);
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        addCallEdge(node.resolveMethodBinding(), node);
        return true;
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        addCallEdge(node.resolveMethodBinding(), node);
        return true;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        addCallEdge(node.resolveMethodBinding(), node);
        return true;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        addCallEdge(node.resolveMethodBinding(), node);
        return true;
    }

    private void addCallEdge(IMethodBinding binding, ASTNode site) {
        String target = sourceMethodTarget(binding);
        String owner = ownerOf(site);
        if (target != null && owner != null) {
            edges.add(new GraphEdge(owner, target, EdgeKind.CALLS));
        }
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        if (node.getAnonymousClassDeclaration() != null) {
            return true;
        }
        addCreationEdge(node.resolveConstructorBinding(), node);
        return true;
    }

    @Override
    public boolean visit(CreationReference node) {
        addCreationEdge(node.resolveMethodBinding(), node);
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        addCreationEdge(node.resolveConstructorBinding(), node);
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        addCreationEdge(node.resolveConstructorBinding(), node);
        return true;
    }

    private void addCreationEdge(IMethodBinding ctor, ASTNode site) {
        if (ctor == null) {
            return;
        }
        ITypeBinding declaring = ctor.getDeclaringClass();
        if (declaring == null || !declaring.isFromSource()) {
            return;
        }
        String owner = ownerOf(site);
        if (owner == null) {
            return;
        }
        // Implicit constructors have no method node: target the type instead.
        String target = ctor.isDefaultConstructor() ? typeKey(declaring) : methodKey(ctor);
        if (target != null) {
            edges.add(new GraphEdge(owner, target, EdgeKind.CREATES));
        }
    }

    // ========== Field access edges ==========

    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return false;
        }
        if (!(node.resolveBinding() instanceof IVariableBinding variable) || !variable.isField()) {
            return false;
        }
        String target = fieldKey(variable);
        String owner = ownerOf(node);
        if (target == null || owner == null) {
            return false;
        }
        for (EdgeKind kind : accessKinds(node)) {
            edges.add(new GraphEdge(owner, target, kind));
        }
        return false;
    }

    /** Determines read/write from the outermost access expression's context. */
    private static Set<EdgeKind> accessKinds(SimpleName name) {
        ASTNode access = name;
        while (access.getParent() instanceof QualifiedName || access.getParent() instanceof FieldAccess) {
            access = access.getParent();
        }
        ASTNode parent = access.getParent();
        if (parent instanceof Assignment assignment && assignment.getLeftHandSide() == access) {
            return assignment.getOperator() == Assignment.Operator.ASSIGN
                ? Set.of(EdgeKind.WRITES)
                : Set.of(EdgeKind.READS, EdgeKind.WRITES);
        }
        if (parent instanceof PrefixExpression prefix
            && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)) {
            return Set.of(EdgeKind.READS, EdgeKind.WRITES);
        }
        if (parent instanceof PostfixExpression) {
            return Set.of(EdgeKind.READS, EdgeKind.WRITES);
        }
        return Set.of(EdgeKind.READS);
    }

    // ========== Ownership and keys ==========

    /**
     * Nearest enclosing named method key; field initializers and initializer
     * blocks (and bodies of anonymous/local types whose methods have no key)
     * attribute to the enclosing named type.
     */
    private String ownerOf(ASTNode site) {
        for (ASTNode current = site.getParent(); current != null; current = current.getParent()) {
            if (current instanceof MethodDeclaration method) {
                String key = methodKey(method.resolveBinding());
                if (key != null) {
                    return key;
                }
            } else if (current instanceof FieldDeclaration || current instanceof Initializer) {
                ASTNode type = current.getParent();
                if (type instanceof AbstractTypeDeclaration typeDecl) {
                    String key = typeKey(typeDecl.resolveBinding());
                    if (key != null) {
                        return key;
                    }
                }
            }
        }
        return null;
    }

    private String sourceMethodTarget(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        ITypeBinding declaring = binding.getDeclaringClass();
        if (declaring == null || !declaring.isFromSource()) {
            return null;
        }
        return methodKey(binding);
    }

    private static String typeKey(ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        String qualified = binding.getErasure().getQualifiedName();
        return qualified.isEmpty() ? null : qualified;
    }

    private static String methodKey(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        String typeKey = typeKey(declaring);
        if (typeKey == null) {
            return null;
        }
        String name = declaration.isConstructor() ? declaring.getName() : declaration.getName();
        String params = Arrays.stream(declaration.getParameterTypes())
            .map(ITypeBinding::getName)
            .collect(Collectors.joining(","));
        return typeKey + "#" + name + "(" + params + ")";
    }

    private static String fieldKey(IVariableBinding binding) {
        if (binding == null || binding.getDeclaringClass() == null
            || !binding.getDeclaringClass().isFromSource()) {
            return null;
        }
        String typeKey = typeKey(binding.getDeclaringClass());
        return typeKey == null ? null : typeKey + "#" + binding.getName();
    }

    private int lineOf(ASTNode node) {
        return ast.getLineNumber(node.getStartPosition()) - 1;
    }
}
