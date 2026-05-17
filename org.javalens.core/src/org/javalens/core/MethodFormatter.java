package org.javalens.core;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Formats method signatures into the canonical informational string used by
 * JavaLens tools that report method identity to the AI consumer.
 *
 * <p>Canonical form: {@code name(Type1 p1, Type2 p2): ReturnType}. Constructors
 * omit the return-type suffix: {@code Foo(int x)}. Parameter names are included
 * when the JDT compiler captured them (which is always the case for source);
 * positional fallback ({@code arg0}, {@code arg1}, …) is supplied for binary methods
 * with no debug info.
 *
 * <p>Used by analyze_type, analyze_method, analyze_file, get_symbol_info,
 * get_super_method, get_type_members, get_document_symbols, get_enclosing_element,
 * and get_method_at_position. Tools that need a different shape (e.g.
 * call-hierarchy's compact {@code name(Type1, Type2)} for terse listings) build
 * their own — this helper is for the informational format, not the only format.
 */
public final class MethodFormatter {

    private MethodFormatter() {}

    /**
     * Build the canonical informational signature for {@code method}.
     *
     * @throws JavaModelException if JDT cannot resolve the method's parameter
     *     or return types — propagated so callers can decide whether to fall
     *     back or surface as an internal error.
     */
    public static String signature(IMethod method) throws JavaModelException {
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
            if (i < paramNames.length) {
                sig.append(" ").append(paramNames[i]);
            }
        }
        sig.append(")");
        if (!method.isConstructor()) {
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        return sig.toString();
    }

    /**
     * Return type as a simple name (e.g. {@code "int"}, {@code "String"}). Returns
     * {@code null} for constructors — the canonical form omits return type for
     * those.
     */
    public static String returnTypeSimpleName(IMethod method) throws JavaModelException {
        if (method.isConstructor()) return null;
        return Signature.getSimpleName(Signature.toString(method.getReturnType()));
    }
}
