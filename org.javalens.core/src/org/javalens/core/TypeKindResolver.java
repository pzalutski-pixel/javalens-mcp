package org.javalens.core;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Returns the lowercase kind label for an {@link IType}: one of
 * {@code class}, {@code interface}, {@code enum}, {@code record}, {@code annotation}.
 *
 * <p>Lowercase matches Java source keywords and the {@code @interface} declaration
 * form. Callers should never branch on capitalization.
 *
 * <p>Ordering matters: a Java {@code @interface} reports both {@code isAnnotation()=true}
 * and {@code isInterface()=true} via JDT. {@code isAnnotation()} must be checked first
 * so annotations don't classify as interfaces.
 */
public final class TypeKindResolver {

    private TypeKindResolver() {}

    /** The kind of {@code type}; a type JDT cannot read fails, naming the type. */
    public static String kindOf(IType type) {
        try {
            if (type.isAnnotation()) return "annotation";
            if (type.isInterface()) return "interface";
            if (type.isEnum()) return "enum";
            if (type.isRecord()) return "record";
            return "class";
        } catch (JavaModelException e) {
            throw unreadable(type, e);
        }
    }

    /** The failure to read the kind of {@code element}: JDT's message, the element named. */
    static IllegalStateException unreadable(IJavaElement element, JavaModelException e) {
        return new IllegalStateException("Cannot read the kind of " + element.getElementName() + ": " + e.getMessage(), e);
    }
}
