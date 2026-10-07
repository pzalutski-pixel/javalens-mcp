package org.javalens.mcp.tools;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How every tool names the Java elements it reports, including the elements that have no
 * name in the source.
 *
 * <ul>
 *   <li>A type's own name is its simple name; an anonymous class's is its type-qualified name,
 *       its qualified name without the package ({@code Outer$1}).</li>
 *   <li>A type named as the container of something is named fully qualified, with {@code $}
 *       between nested types ({@code pkg.Outer$Inner}, {@code pkg.Outer$1}).</li>
 *   <li>An initializer is named {@value #INITIALIZER}; the default package
 *       {@value #DEFAULT_PACKAGE}.</li>
 *   <li>A lambda expression is no member of its own: what lies in its body belongs to the
 *       nearest enclosing member that has a name.</li>
 *   <li>A type reached through a compiler binding is named through its model element, as the
 *       same type reached through the model is.</li>
 *   <li>A method's key is {@code Type#name(params)}, its declaring type named fully qualified
 *       ({@code pkg.Outer$Inner#run(String)}), as the project graph keys its methods.</li>
 * </ul>
 */
final class ElementNames {

    /** The name of an initializer, which has none in the source. */
    static final String INITIALIZER = "<initializer>";

    /** The name of the default package, which has none in the source. */
    static final String DEFAULT_PACKAGE = "(default package)";

    private ElementNames() {
    }

    /**
     * A type's own name: its simple name, or for an anonymous class its type-qualified name
     * ({@code Outer$1}).
     */
    static String typeName(IType type) throws JavaModelException {
        IType model = modelType(type);
        return model.isAnonymous() ? model.getTypeQualifiedName() : model.getElementName();
    }

    /**
     * A type's fully qualified name, with {@code $} between nested types; an anonymous class's
     * ends in its number in its enclosing type ({@code pkg.Outer$1}).
     */
    static String qualifiedName(IType type) {
        return modelType(type).getFullyQualifiedName();
    }

    /**
     * An element's name: a type's as {@link #typeName}, an initializer's as
     * {@value #INITIALIZER}, any other element's its own.
     */
    static String name(IJavaElement element) throws JavaModelException {
        if (element instanceof IType type) {
            return typeName(type);
        }
        if (element.getElementType() == IJavaElement.INITIALIZER) {
            return INITIALIZER;
        }
        return element.getElementName();
    }

    /** A package's name; the default package's as {@value #DEFAULT_PACKAGE}. */
    static String packageName(String name) {
        return name.isEmpty() ? DEFAULT_PACKAGE : name;
    }

    /**
     * The member that holds {@code element} and has a name: {@code element} itself, or, when it
     * is a lambda expression or the method of one, the nearest enclosing element that is neither.
     */
    static IJavaElement namedMember(IJavaElement element) {
        IJavaElement current = element;
        while (current != null && isLambda(current)) {
            current = current.getParent();
        }
        return current;
    }

    /** The nearest method enclosing {@code element}, itself included, that is not a lambda's. */
    static IMethod enclosingMethod(IJavaElement element) {
        IJavaElement current = element;
        while (current != null && !(current instanceof IMethod method && !method.isLambdaMethod())) {
            current = current.getParent();
        }
        return (IMethod) current;
    }

    /** The nearest type enclosing {@code element}, itself included, that is not a lambda expression. */
    static IType enclosingType(IJavaElement element) {
        IJavaElement current = element;
        while (current != null && !(current instanceof IType type && !type.isLambda())) {
            current = current.getParent();
        }
        return (IType) current;
    }

    /**
     * The fully qualified name of the class or interface {@code binding} denotes, without type
     * arguments, named as {@link #qualifiedName(IType)} names its model element.
     */
    static String qualifiedName(ITypeBinding binding) {
        return qualifiedName(typeOf(binding));
    }

    /**
     * The key of the method {@code binding} denotes, {@code Type#name(params)}: the declaring
     * type of its declaration named as {@link #qualifiedName(ITypeBinding)} names it, a
     * constructor by its type's simple name, each parameter type by its binding's name.
     */
    static String methodKey(IMethodBinding binding) {
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        String name = declaration.isConstructor() ? declaring.getName() : declaration.getName();
        String params = Arrays.stream(declaration.getParameterTypes())
            .map(ITypeBinding::getName)
            .collect(Collectors.joining(","));
        return qualifiedName(declaring) + "#" + name + "(" + params + ")";
    }

    /**
     * The type {@code binding} denotes as it is written in a signature: the binding's name, with
     * type arguments when it has them; a captured wildcard by the wildcard it captures
     * ({@code ? extends Foo}, {@code ? super Foo}, {@code ?}); an anonymous class by
     * {@link #typeName}. An array is named by its element type followed by its dimensions, a
     * parameterized type by its generic type followed by its type arguments, each named as this
     * names a type, so a capture or an anonymous class within them is named too.
     */
    static String typeExpression(ITypeBinding binding) {
        if (binding.isCapture()) {
            return typeExpression(binding.getWildcard());
        }
        if (binding.isWildcardType()) {
            ITypeBinding bound = binding.getBound();
            return bound == null ? "?" : (binding.isUpperbound() ? "? extends " : "? super ") + typeExpression(bound);
        }
        if (binding.isAnonymous()) {
            return modelType(typeOf(binding)).getTypeQualifiedName();
        }
        if (binding.isArray()) {
            return typeExpression(binding.getElementType()) + "[]".repeat(binding.getDimensions());
        }
        if (binding.isParameterizedType()) {
            return binding.getTypeDeclaration().getName() + Arrays.stream(binding.getTypeArguments())
                .map(ElementNames::typeExpression)
                .collect(Collectors.joining(",", "<", ">"));
        }
        return binding.getName();
    }

    /**
     * The model element of the class or interface {@code binding} denotes. An array, a primitive
     * or a type variable denotes no class or interface: JDT would answer for an array with its
     * element type's element, so these fail rather than name the wrong type.
     */
    private static IType typeOf(ITypeBinding binding) {
        if (!binding.isArray() && !binding.isPrimitive() && !binding.isTypeVariable()
                && binding.getTypeDeclaration().getJavaElement() instanceof IType type) {
            return type;
        }
        throw new IllegalStateException("No class or interface element for the type " + binding.getKey());
    }

    /**
     * {@code type} as the Java model holds it. A handle that a search creates for an anonymous
     * class carries no number of its own, so its qualified name would read {@code $1} whatever
     * its place; the model's instance, found among its parent's children, carries the right one.
     * A handle the model does not hold names itself.
     */
    private static IType modelType(IType type) {
        if (type.isBinary() || !(type.getParent() instanceof IParent parent)) {
            return type;
        }
        try {
            for (IJavaElement child : parent.getChildren()) {
                if (child.equals(type)) {
                    return (IType) child;
                }
            }
        } catch (JavaModelException e) {
            // The parent is gone from the model: the handle is all there is to name.
        }
        return type;
    }

    private static boolean isLambda(IJavaElement element) {
        return (element instanceof IMethod method && method.isLambdaMethod())
            || (element instanceof IType type && type.isLambda());
    }
}
