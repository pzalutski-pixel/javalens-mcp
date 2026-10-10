package org.javalens.mcp.fixtures;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * A loaded service that fails where a test says: each method named with {@link #failing} throws an
 * IllegalStateException carrying {@link #FAILURE}, and with {@link #withoutSyntaxTrees} every
 * compilation unit it hands out answers {@code reconcile} with no syntax tree, as JDT's reconcile
 * does for a unit whose source it cannot read. Every other call is the loaded service's own.
 */
public final class FaultyService extends JdtServiceImpl {

    /** The message of every failure this service throws. */
    public static final String FAILURE = "injected failure";

    private final Set<String> failing = new HashSet<>();
    private boolean withoutSyntaxTrees;

    /** This service, with each of {@code methods} throwing. */
    public FaultyService failing(String... methods) {
        failing.addAll(Set.of(methods));
        return this;
    }

    /** This service, its compilation units answering reconcile with no syntax tree. */
    public FaultyService withoutSyntaxTrees() {
        withoutSyntaxTrees = true;
        return this;
    }

    private void fail(String method) {
        if (failing.contains(method)) {
            throw new IllegalStateException(FAILURE);
        }
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        fail("getContextLine");
        return super.getContextLine(cu, offset);
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        fail("getLineNumber");
        return super.getLineNumber(cu, offset);
    }

    @Override
    public IType findType(String typeName) {
        fail("findType");
        return super.findType(typeName);
    }

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        fail("getCompilationUnit");
        ICompilationUnit unit = super.getCompilationUnit(filePath);
        if (unit == null || !withoutSyntaxTrees) {
            return unit;
        }
        return (ICompilationUnit) Proxy.newProxyInstance(FaultyService.class.getClassLoader(),
            new Class<?>[] { ICompilationUnit.class }, (proxy, method, args) -> {
                if (method.getName().equals("reconcile")) {
                    return null;
                }
                try {
                    return method.invoke(unit, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
