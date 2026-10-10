package org.javalens.core.search;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.javalens.core.Deadline;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Java model's element for a compiler binding, read in one parsed compilation unit or, for a
 * binding of a class file, from the binding alone.
 *
 * <p>A type or method declared in the unit is found by the position of its declaration and
 * {@link ICompilationUnit#getElementAt}: the model numbers the local and anonymous classes of a
 * member in source order, lambda bodies included, while the handle a binding makes for a class in a
 * lambda body is numbered within that lambda (jdt.core HandleFactory, :235-246), so the handle can
 * name a sibling or no class at all. Any other type or method is a named member or top-level type
 * of another unit, or of a class file: a local or anonymous class can only be named within its own
 * unit. Its handle, {@code getJavaElement()}, names it exactly.
 */
final class ModelElements {

    private final ICompilationUnit unit;
    private final CompilationUnit ast;

    /** Elements read in {@code ast}, the parse of {@code unit}; both null for bindings of class files. */
    ModelElements(ICompilationUnit unit, CompilationUnit ast) {
        this.unit = unit;
        this.ast = ast;
    }

    /**
     * The model element of the class or interface {@code binding} denotes, its type arguments
     * erased, or null when the model holds none (a missing type).
     */
    IType typeOf(ITypeBinding binding) throws JavaModelException {
        ITypeBinding declaration = binding.getTypeDeclaration();
        if (ast != null && declaration.isFromSource() && ast.findDeclaringNode(declaration) instanceof ASTNode node
                && isTypeDeclaration(node)) {
            return typeAt(node);
        }
        return declaration.getJavaElement() instanceof IType type ? type : null;
    }

    /**
     * The model element of the method {@code binding} denotes, its type arguments erased, or null
     * when the model holds none (a method the compiler supplies, such as a default constructor).
     */
    IMethod methodOf(IMethodBinding binding) throws JavaModelException {
        IMethodBinding declaration = binding.getMethodDeclaration();
        if (ast != null && declaration.getDeclaringClass().isFromSource()
                && ast.findDeclaringNode(declaration) instanceof MethodDeclaration node) {
            return methodAt(node);
        }
        return declaration.getJavaElement() instanceof IMethod method && method.exists() ? method : null;
    }

    /** The model element declared by {@code declaration}, a type declaration of this unit. */
    IType typeAt(ASTNode declaration) throws JavaModelException {
        if (declaration instanceof ImplicitTypeDeclaration) {
            // The implicitly declared class of a compact source file has no name in the source
            // (its name node has no position): the model names it after its file, the primary
            // type of its unit.
            return unit.findPrimaryType();
        }
        int offset = declaration instanceof AbstractTypeDeclaration named
            ? named.getName().getStartPosition()
            : declaration.getStartPosition();
        return unit.getElementAt(offset) instanceof IType type ? type : null;
    }

    /** The model element declared by {@code declaration}, a method declaration of this unit. */
    IMethod methodAt(MethodDeclaration declaration) throws JavaModelException {
        return unit.getElementAt(declaration.getName().getStartPosition()) instanceof IMethod method ? method : null;
    }

    /** The binding {@code declaration}, a type declaration, declares. */
    static ITypeBinding bindingOf(ASTNode declaration) {
        return declaration instanceof AbstractTypeDeclaration named
            ? named.resolveBinding()
            : ((AnonymousClassDeclaration) declaration).resolveBinding();
    }

    /** Whether {@code node} declares a class or interface: a named type or an anonymous class body. */
    static boolean isTypeDeclaration(ASTNode node) {
        return node instanceof AbstractTypeDeclaration || node instanceof AnonymousClassDeclaration;
    }

    /** Every type declaration of {@code ast}, in source order, nested ones after their container. */
    static void forEachTypeDeclaration(CompilationUnit ast, Consumer<ASTNode> each) {
        ast.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (isTypeDeclaration(node)) {
                    each.accept(node);
                }
            }
        });
    }

    /**
     * Parses {@code units} with bindings in one batch and hands each to {@code eachUnit}, and
     * resolves {@code keys}, binding keys of class-file elements, handing each binding to
     * {@code eachBinding}. Bindings are used within the callbacks only, where the batch's lookup
     * environment still answers them.
     */
    static void parse(IJavaProject project, Collection<ICompilationUnit> units, Collection<String> keys,
                      BiConsumer<ICompilationUnit, CompilationUnit> eachUnit, BiConsumer<String, IBinding> eachBinding) {
        if (units.isEmpty() && keys.isEmpty()) {
            return;
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(project);
        parser.setResolveBindings(true);
        parser.createASTs(units.toArray(ICompilationUnit[]::new), keys.toArray(String[]::new), new ASTRequestor() {
            @Override
            public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
                eachUnit.accept(source, ast);
            }

            @Override
            public void acceptBinding(String bindingKey, IBinding binding) {
                eachBinding.accept(bindingKey, binding);
            }
        }, Deadline.monitor());
    }

    /** Whether {@code element} is declared in a method, field or initializer: in a local or anonymous class. */
    static boolean isInLocalScope(IJavaElement element) {
        for (IJavaElement current = element.getParent(); current != null; current = current.getParent()) {
            int kind = current.getElementType();
            if (kind == IJavaElement.METHOD || kind == IJavaElement.FIELD || kind == IJavaElement.INITIALIZER) {
                return true;
            }
        }
        return false;
    }

    /** The binding of {@code type} declared in {@code ast}, the parse of its unit, or null. */
    ITypeBinding declarationOf(IType type) throws JavaModelException {
        ITypeBinding[] found = { null };
        JavaModelException[] failure = { null };
        forEachTypeDeclaration(ast, node -> {
            if (found[0] == null && failure[0] == null) {
                try {
                    if (type.equals(typeAt(node))) {
                        found[0] = bindingOf(node);
                    }
                } catch (JavaModelException e) {
                    failure[0] = e;
                }
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return found[0];
    }

    /** The binding of {@code method} declared in {@code ast}, the parse of its unit, or null. */
    IMethodBinding declarationOf(IMethod method) throws JavaModelException {
        IMethodBinding[] found = { null };
        JavaModelException[] failure = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (found[0] == null && failure[0] == null) {
                    try {
                        if (method.equals(methodAt(node))) {
                            found[0] = node.resolveBinding();
                        }
                    } catch (JavaModelException e) {
                        failure[0] = e;
                    }
                }
                return found[0] == null;
            }

            // An element of an annotation type, a method the model holds as any other.
            @Override
            public boolean visit(AnnotationTypeMemberDeclaration node) {
                if (found[0] == null && failure[0] == null) {
                    try {
                        if (unit.getElementAt(node.getName().getStartPosition()) instanceof IMethod element
                                && method.equals(element)) {
                            found[0] = node.resolveBinding();
                        }
                    } catch (JavaModelException e) {
                        failure[0] = e;
                    }
                }
                return false;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return found[0];
    }
}
