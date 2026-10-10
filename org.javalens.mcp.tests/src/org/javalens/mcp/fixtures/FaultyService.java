package org.javalens.mcp.fixtures;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.search.SearchService;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loaded service that fails where a test says. Every other call is the loaded service's own.
 *
 * <ul>
 *   <li>{@link #failing} and {@link #failingFrom}: a method of this service throws an
 *       IllegalStateException carrying {@link #FAILURE}, from its n-th call on.</li>
 *   <li>{@link #failingModel} and {@link #failingModelFrom}: a method of the Java elements this
 *       service hands out throws a JavaModelException whose status, of plug-in {@link #PLUGIN},
 *       carries {@link #FAILURE}, as JDT throws one for an element it cannot read; only a method JDT declares to throw
 *       it is made to fail. A fault named
 *       {@code IType.getSourceRange} fails that method of types only; one named
 *       {@code getSourceRange}, of every element. The elements of the types, methods and fields
 *       found by {@code findType}, {@code getElementAtPosition} and {@code getTypeAtPosition},
 *       and of the declarations a symbol search reports, are handed out so, and so is every
 *       type, method, field, initializer, package fragment and root they answer with; the
 *       compilation units the service hands out are, when a fault names
 *       {@code ICompilationUnit} or with {@link #withoutSyntaxTrees}; the project is, when a fault
 *       names {@code IJavaProject}.</li>
 *   <li>{@link #failingSearch}: a method of the search service throws that JavaModelException.</li>
 *   <li>{@link #withoutSyntaxTrees}: every compilation unit answers {@code reconcile} with no
 *       syntax tree, as JDT's reconcile does for a unit whose source it cannot read.</li>
 * </ul>
 *
 * The search service is handed the elements themselves, never the failing ones, so a search
 * runs as it would.
 */
public final class FaultyService extends JdtServiceImpl {

    /** The message of every failure this service throws. */
    public static final String FAILURE = "injected failure";

    /** The plug-in of the status of every JavaModelException this service throws. */
    public static final String PLUGIN = "org.javalens.mcp.tests";

    private final Map<String, Integer> serviceFaults = new HashMap<>();
    private final Map<String, Integer> modelFaults = new HashMap<>();
    private final Set<String> searchFaults = new HashSet<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private boolean withoutSyntaxTrees;
    private boolean handOutUnits;
    private boolean handOutProject;
    /** Set while the loaded service reads an element at a position: it parses the unit itself. */
    private boolean readingPosition;

    /** This service, with each of {@code methods} throwing. */
    public FaultyService failing(String... methods) {
        for (String method : methods) {
            serviceFaults.put(method, 1);
        }
        return this;
    }

    /** This service, with {@code method} throwing from its {@code call}-th call on, counting from 1. */
    public FaultyService failingFrom(String method, int call) {
        serviceFaults.put(method, call);
        return this;
    }

    /** This service, the elements it hands out failing each of {@code methods}. */
    public FaultyService failingModel(String... methods) {
        for (String method : methods) {
            modelFaults.put(method, 1);
        }
        return this;
    }

    /**
     * This service, the elements it hands out failing {@code method} from its {@code call}-th
     * call on, counting the calls on every such element from 1.
     */
    public FaultyService failingModelFrom(String method, int call) {
        modelFaults.put(method, call);
        return this;
    }

    /** This service, its search service failing each of {@code methods}. */
    public FaultyService failingSearch(String... methods) {
        searchFaults.addAll(List.of(methods));
        return this;
    }

    /** This service, its compilation units answering reconcile with no syntax tree. */
    public FaultyService withoutSyntaxTrees() {
        withoutSyntaxTrees = true;
        return this;
    }

    /**
     * This service, handing out its compilation units, and the elements they answer with, as it
     * hands out the elements a model fault applies to. JDT's parser reads a unit through its own
     * classes, so a tool that parses a unit cannot be handed one out.
     */
    public FaultyService handingOutUnits() {
        handOutUnits = true;
        return this;
    }

    /** This service, handing out its project, and the elements it answers with, so. */
    public FaultyService handingOutProject() {
        handOutProject = true;
        return this;
    }

    /** Starts counting the calls of every fault anew, as a new tool call does. */
    public void resetCalls() {
        calls.clear();
    }

    /** The JavaModelException every injected JDT failure is. */
    public static JavaModelException modelFailure() {
        return new JavaModelException(new Status(IStatus.ERROR, PLUGIN, FAILURE));
    }

    private void fail(String method) {
        Integer from = serviceFaults.get(method);
        if (from != null && calls.merge("service:" + method, 1, Integer::sum) >= from) {
            throw new IllegalStateException(FAILURE);
        }
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        fail("getContextLine");
        return super.getContextLine(unwrap(cu), offset);
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        fail("getLineNumber");
        return super.getLineNumber(unwrap(cu), offset);
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        fail("getColumnNumber");
        return super.getColumnNumber(unwrap(cu), offset);
    }

    @Override
    public IType findType(String typeName) {
        fail("findType");
        return members(super.findType(typeName));
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        fail("getElementAtPosition");
        readingPosition = true;
        try {
            return members(super.getElementAtPosition(filePath, line, column));
        } finally {
            readingPosition = false;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        fail("getTypeAtPosition");
        readingPosition = true;
        try {
            return members(super.getTypeAtPosition(filePath, line, column));
        } finally {
            readingPosition = false;
        }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        fail("getAllJavaFiles");
        return super.getAllJavaFiles();
    }

    @Override
    public IJavaProject getJavaProject() {
        fail("getJavaProject");
        IJavaProject project = super.getJavaProject();
        return project != null && proxiesProject() ? (IJavaProject) proxy(project, IJavaProject.class) : project;
    }

    @Override
    public SearchService getSearchService() {
        SearchService search = super.getSearchService();
        if (search == null || (searchFaults.isEmpty() && !proxiesMembers())) {
            return search;
        }
        return new FaultySearchService(super.getJavaProject(), this);
    }

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        fail("getCompilationUnit");
        ICompilationUnit unit = super.getCompilationUnit(filePath);
        return unit != null && proxiesUnits() && !readingPosition
            ? (ICompilationUnit) proxy(unit, ICompilationUnit.class) : unit;
    }

    /** Whether the search service fails {@code method}. */
    boolean searchFails(String method) {
        return searchFaults.contains(method);
    }

    /** Whether the elements a symbol search reports are handed out failing. */
    boolean proxiesMembers() {
        return !modelFaults.isEmpty() || proxiesUnits() || proxiesProject();
    }

    private boolean proxiesUnits() {
        return withoutSyntaxTrees || handOutUnits
            || modelFaults.keySet().stream().anyMatch(key -> key.startsWith("ICompilationUnit."));
    }

    private boolean proxiesProject() {
        return handOutProject || modelFaults.keySet().stream().anyMatch(key -> key.startsWith("IJavaProject."));
    }

    /** {@code element} as this service hands it out: failing as configured, when it is a member. */
    @SuppressWarnings("unchecked")
    <T extends IJavaElement> T members(T element) {
        if (element == null || !proxiesMembers()) {
            return element;
        }
        Class<?> face = handedOut(element.getClass());
        return face == null ? element : (T) proxy(element, face);
    }

    /** The interface the service hands out an element of {@code type} under, or null for none. */
    private Class<?> handedOut(Class<?> type) {
        if (IType.class.isAssignableFrom(type)) return IType.class;
        if (IMethod.class.isAssignableFrom(type)) return IMethod.class;
        if (IField.class.isAssignableFrom(type)) return IField.class;
        if (IInitializer.class.isAssignableFrom(type)) return IInitializer.class;
        if (IPackageFragment.class.isAssignableFrom(type)) return IPackageFragment.class;
        if (IPackageFragmentRoot.class.isAssignableFrom(type)) return IPackageFragmentRoot.class;
        if (ICompilationUnit.class.isAssignableFrom(type)) return proxiesUnits() ? ICompilationUnit.class : null;
        if (IJavaProject.class.isAssignableFrom(type)) return proxiesProject() ? IJavaProject.class : null;
        return null;
    }

    private Object proxy(Object real, Class<?> face) {
        InvocationHandler handler = new Handed(real, face.getSimpleName());
        return Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[] { face }, handler);
    }

    /** {@code value} itself when it is an element this service handed out failing; else {@code value}. */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(T value) {
        if (value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof Handed handed) {
            return (T) handed.real;
        }
        return value;
    }

    /** Hands out one element: its configured faults fail, every other call is the element's own. */
    private final class Handed implements InvocationHandler {
        private final Object real;
        private final String face;

        Handed(Object real, String face) {
            this.real = real;
            this.face = face;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("equals") && args != null && args.length == 1) {
                return real.equals(unwrap(args[0]));
            }
            if (withoutSyntaxTrees && face.equals("ICompilationUnit") && name.equals("reconcile")) {
                return null;
            }
            for (String key : List.of(face + "." + name, name)) {
                Integer from = modelFaults.get(key);
                if (from != null && calls.merge("model:" + key, 1, Integer::sum) >= from) {
                    throw modelFailure();
                }
            }
            Object result;
            try {
                result = method.invoke(real, unwrapAll(args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            return handOut(result);
        }

        private Object[] unwrapAll(Object[] args) {
            if (args == null) {
                return null;
            }
            Object[] unwrapped = args.clone();
            for (int i = 0; i < unwrapped.length; i++) {
                unwrapped[i] = unwrap(unwrapped[i]);
            }
            return unwrapped;
        }

        private Object handOut(Object result) {
            if (result instanceof IJavaElement element) {
                Class<?> face = handedOut(element.getClass());
                return face == null ? result : proxy(element, face);
            }
            if (result != null && result.getClass().isArray()
                    && IJavaElement.class.isAssignableFrom(result.getClass().getComponentType())) {
                int length = Array.getLength(result);
                Object copy = Array.newInstance(result.getClass().getComponentType(), length);
                for (int i = 0; i < length; i++) {
                    Array.set(copy, i, handOut(Array.get(result, i)));
                }
                return copy;
            }
            return result;
        }
    }
}
