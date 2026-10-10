package org.javalens.core.fixtures;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

/**
 * Java elements that fail where a test says, as JDT fails for an element it cannot read: each
 * named method throws {@link #failure()}; every other call is the element's own.
 */
public final class FailingElements {

    /** The message of every failure. */
    public static final String FAILURE = "injected failure";

    private FailingElements() {
    }

    /** The JavaModelException every failure is. */
    public static JavaModelException failure() {
        return new JavaModelException(new Status(IStatus.ERROR, "org.javalens.core.tests", FAILURE));
    }

    /** {@code real} under {@code face}, each of {@code methods} throwing {@link #failure()}. */
    public static <T> T failing(T real, Class<T> face, String... methods) {
        return answering(real, face, Map.of(), Set.of(methods));
    }

    /** {@code unit}, its source null, as a buffer JDT has closed answers. */
    public static ICompilationUnit withoutSource(ICompilationUnit unit) {
        return answering(unit, ICompilationUnit.class, Map.of("getSource", ""), Set.of());
    }

    /**
     * {@code real} under {@code face}: each method of {@code answers} answers the element it maps to
     * (an empty string for null), each of {@code failing} throws {@link #failure()}, every other
     * call is {@code real}'s own.
     */
    public static <T> T answering(T real, Class<T> face, Map<String, Object> answers, Set<String> failing) {
        Object proxy = Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[] { face },
            (self, method, args) -> {
                String name = method.getName();
                if (failing.contains(name)) {
                    throw failure();
                }
                if (answers.containsKey(name)) {
                    Object answer = answers.get(name);
                    return "".equals(answer) ? null : answer;
                }
                if (name.equals("equals") && args != null && args.length == 1) {
                    return real.equals(args[0]);
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        return face.cast(proxy);
    }

    /** {@code element}'s compilation unit, as {@code getAncestor} answers it. */
    public static ICompilationUnit unitOf(IJavaElement element) {
        return (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
    }
}
