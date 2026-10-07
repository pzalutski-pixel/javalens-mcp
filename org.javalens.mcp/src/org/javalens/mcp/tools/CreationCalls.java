package org.javalens.mcp.tools;

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * The constructor a class instance creation is a call of, as the outgoing call tools report it.
 *
 * <p>Creating a named class calls the constructor the creation resolves to. Creating an anonymous
 * class runs the constructor the compiler generates for it, which passes the creation's arguments
 * to a constructor of the anonymous class's superclass ({@code Object()} when the anonymous class
 * implements an interface): the creation is a call of that superclass constructor, as JDT's own
 * call hierarchy maps an anonymous class to its named supertype (jdt.core.manipulation 1.23,
 * CalleeAnalyzerVisitor.addMethodCall, lines 268-283) and as a search for references to that
 * constructor finds the creation. The created class stays the anonymous class.
 */
final class CreationCalls {

    private CreationCalls() {
    }

    /**
     * The constructor {@code creation} is a call of, or null when it does not resolve. For an
     * anonymous class, the superclass constructor whose parameter types its generated constructor
     * takes; when bindings recovered from errors match none, its generated constructor.
     */
    static IMethodBinding constructorCalled(ClassInstanceCreation creation) {
        IMethodBinding resolved = creation.resolveConstructorBinding();
        if (resolved == null || creation.getAnonymousClassDeclaration() == null) {
            return resolved;
        }
        ITypeBinding superclass = resolved.getDeclaringClass().getSuperclass();
        if (superclass != null) {
            for (IMethodBinding candidate : superclass.getDeclaredMethods()) {
                if (candidate.isConstructor() && sameParameterTypes(candidate, resolved)) {
                    return candidate;
                }
            }
        }
        return resolved;
    }

    /**
     * The key a list of callees tells {@code creation}'s call apart by: the constructor's, and for
     * an anonymous class also the class it creates, so the creation of each anonymous class is
     * listed.
     */
    static String key(ClassInstanceCreation creation, IMethodBinding constructor) {
        IMethodBinding resolved = creation.resolveConstructorBinding();
        return creation.getAnonymousClassDeclaration() == null || resolved == null
            ? constructor.getKey()
            : constructor.getKey() + "|" + resolved.getDeclaringClass().getKey();
    }

    /** The class {@code creation} creates: the anonymous class, or the class it names. */
    static ITypeBinding createdClass(ClassInstanceCreation creation, IMethodBinding constructor) {
        IMethodBinding resolved = creation.resolveConstructorBinding();
        return resolved == null ? constructor.getDeclaringClass() : resolved.getDeclaringClass();
    }

    private static boolean sameParameterTypes(IMethodBinding a, IMethodBinding b) {
        ITypeBinding[] left = a.getParameterTypes();
        ITypeBinding[] right = b.getParameterTypes();
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!left[i].isEqualTo(right[i])) {
                return false;
            }
        }
        return true;
    }
}
