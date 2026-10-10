package org.javalens.core;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Canonical lowercase {@code kind} string for any {@link IJavaElement}.
 *
 * <p>Mirrors {@link TypeKindResolver}'s lowercase convention so the {@code kind}
 * field is uniformly cased across every tool that emits one. The earlier
 * inconsistency (type-kind lowercase, element-kind capitalized) created mixed
 * casing inside one field and forced AI consumers to special-case.
 *
 * <p>Field-kind classification additionally distinguishes enum constants and
 * static-final constants. Inputs that are not classified return {@code "unknown"}. An element
 * JDT cannot read fails, naming the element, never takes a default kind.
 */
public final class ElementKindResolver {

    private ElementKindResolver() {}

    /** Lowercase kind for any element. Delegates to {@link TypeKindResolver} for types. */
    public static String kindOf(IJavaElement element) {
        if (element instanceof IType type) {
            return TypeKindResolver.kindOf(type);
        }
        if (element instanceof IMethod method) {
            try {
                return method.isConstructor() ? "constructor" : "method";
            } catch (JavaModelException e) {
                throw TypeKindResolver.unreadable(method, e);
            }
        }
        if (element instanceof IField field) {
            return fieldKindOf(field);
        }
        return switch (element.getElementType()) {
            case IJavaElement.LOCAL_VARIABLE -> "variable";
            case IJavaElement.TYPE_PARAMETER -> "typeParameter";
            case IJavaElement.PACKAGE_FRAGMENT -> "package";
            case IJavaElement.PACKAGE_DECLARATION -> "packageDeclaration";
            case IJavaElement.COMPILATION_UNIT -> "compilationUnit";
            case IJavaElement.INITIALIZER -> "initializer";
            case IJavaElement.IMPORT_DECLARATION -> "import";
            case IJavaElement.JAVA_MODULE -> "module";
            default -> "unknown";
        };
    }

    /**
     * Field-specific classification: enum constants and static-final fields get
     * distinct kinds. Use {@link #kindOf(IJavaElement)} for the dispatching form.
     * A field JDT cannot read fails, naming the field.
     */
    public static String fieldKindOf(IField field) {
        try {
            if (field.isEnumConstant()) return "enumConstant";
            int flags = field.getFlags();
            if (Flags.isStatic(flags) && Flags.isFinal(flags)) return "constant";
        } catch (JavaModelException e) {
            throw TypeKindResolver.unreadable(field, e);
        }
        return "field";
    }
}
