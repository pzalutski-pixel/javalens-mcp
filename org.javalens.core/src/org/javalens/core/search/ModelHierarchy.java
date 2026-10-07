package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.corext.dom.Bindings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The supertypes and subtypes of one type, and the methods that override its methods or that its
 * methods override, as the Java model holds them. Every element listed is the model's own: a local
 * or anonymous class carries the model's name and number ({@code Outer$Local}, {@code Outer$2})
 * whether it is declared in a lambda body or after one, where JDT's type hierarchy drops it or
 * reads another class in its place (HierarchyResolver.rememberAllTypes :511-541 and remember
 * :442-450, numbering through HandleFactory :199-287). A lambda expression is no type of the model:
 * it has no hierarchy here and is no subtype of the interface it implements.
 *
 * <h2>Supertypes</h2>
 * Read from the compiler's bindings at the type's declaration: its superclass
 * ({@code ITypeBinding.getSuperclass()}: none for an interface, for {@code java.lang.Object} and
 * for an annotation type) and its interfaces ({@code getInterfaces()}), each mapped to its model
 * element ({@link ModelElements}). A supertype the compiler cannot resolve is left out.
 *
 * <h2>Subtypes</h2>
 * A direct subtype names the type as its superclass or one of its interfaces. They are found by an
 * {@code IMPLEMENTORS} search for the type, which reports every class or interface naming it in
 * its {@code extends} or {@code implements} clause and every anonymous class created from it
 * (SuperTypeReferenceLocator), each match moved to the model's element at the match position
 * ({@link SearchService#withModelElement}); a lambda it reports is left out, and so is a match the
 * search could not resolve and a class-file copy of a type an earlier classpath entry declares (a
 * module's installed jar beside its sources), which the project's name lookup never resolves to.
 * A local class can be named only within its own compilation unit: its
 * direct subtypes are the type declarations of that unit whose bindings name it. The body of an
 * enum constant names no type, so a source enum's constant bodies are read from the model. All
 * subtypes repeat this from every subtype found until none is new.
 *
 * <p>An implicit root is named by none of its direct subtypes: {@code java.lang.Object} (classes
 * declared without a superclass, anonymous classes of interfaces), {@code java.lang.Enum} (enums),
 * {@code java.lang.Record} (records) and {@code java.lang.annotation.Annotation} (annotation
 * types). Its subtypes from class files are those of JDT's type hierarchy; its source subtypes are
 * every type the project's source units declare, named, local or anonymous, whose supertypes, read
 * as above, include the root. Every source type is read, however many there are: the list is
 * complete and does not depend on what the search index holds, which never names an implicit
 * superclass ({@link #rootSubtypes} gives the JDT source).
 *
 * <h2>Methods</h2>
 * {@link #overriding} is every method of a subtype that overrides the given method as the compiler
 * decides it ({@code IMethodBinding.overrides}, JLS 8.4.8.1: same name, a subsignature with type
 * arguments applied, visible, neither static nor private). {@link #overridden} is the method the
 * given method overrides, as JDT's own quick fixes find it ({@code Bindings.findOverriddenMethod}):
 * the nearest declaration in the superclass chain, else in the interfaces, visible from the
 * method's package. A method the compiler resolves no binding for is refused with a
 * JavaModelException, never answered as overriding nothing.
 *
 * <h2>Order</h2>
 * Superclasses run from the nearest. Interfaces of all supertypes come in declaration order, each
 * followed by its own superinterfaces, then those of the superclass, each listed once; all
 * supertypes interleave them the same way, a superclass after the interfaces of the class below it
 * (the orders of ITypeHierarchy.getAllSuperInterfaces and getAllSupertypes). Subtypes, and the
 * methods overriding a method, come by file, then by where the declaration starts in it: source
 * types by their file's location, compared segment by segment, then by the start of their source
 * range; types of class files after them, by fully qualified name.
 *
 * <p>Each list is computed when first asked for and belongs to this object alone: nothing is kept
 * between calls of {@link SearchService#hierarchyOf}.
 */
public final class ModelHierarchy {

    /** The roots no direct subtype names. */
    private static final Set<String> IMPLICIT_ROOTS = Set.of(
        "java.lang.Object", "java.lang.Enum", "java.lang.Record", "java.lang.annotation.Annotation");

    /** Source types by file, then by start; class-file types after them by fully qualified name. */
    static final Comparator<IType> TYPE_ORDER = ModelHierarchy::compareTypes;

    /** Methods by their declaring types' order, then by start in source or by name. */
    static final Comparator<IMethod> METHOD_ORDER = ModelHierarchy::compareMethods;

    private final IType type;
    private final IJavaProject project;
    private final SearchEngine engine;
    private final IJavaSearchScope scope;

    private final IType superclass;
    private final List<IType> superInterfaces;
    private final List<IType> allSuperclasses;
    private final List<IType> allSuperInterfaces;
    private final List<IType> allSupertypes;

    private List<IType> subtypes;
    private List<IType> allSubtypes;

    ModelHierarchy(IType type, IJavaProject project, SearchEngine engine, IJavaSearchScope scope) throws CoreException {
        if (type.isLambda()) {
            throw new IllegalArgumentException("A lambda expression is no type of the Java model: " + type);
        }
        this.type = type;
        this.project = project;
        this.engine = engine;
        this.scope = scope;
        Supertypes supertypes = supertypesOf(type);
        this.superclass = supertypes.superclass();
        this.superInterfaces = supertypes.superInterfaces();
        this.allSuperclasses = supertypes.allSuperclasses();
        this.allSuperInterfaces = supertypes.allSuperInterfaces();
        this.allSupertypes = supertypes.allSupertypes();
    }

    /** The type this hierarchy is of. */
    public IType type() {
        return type;
    }

    /** The direct superclass, or null for an interface, an annotation type and java.lang.Object. */
    public IType superclass() {
        return superclass;
    }

    /** The direct interfaces, in declaration order. */
    public List<IType> superInterfaces() {
        return superInterfaces;
    }

    /** Every superclass, from the nearest. */
    public List<IType> allSuperclasses() {
        return allSuperclasses;
    }

    /** Every interface of every supertype, each once, in the order the class comment gives. */
    public List<IType> allSuperInterfaces() {
        return allSuperInterfaces;
    }

    /** Every superclass and interface, each once, in the order the class comment gives. */
    public List<IType> allSupertypes() {
        return allSupertypes;
    }

    /** The direct subclasses: the direct subtypes of a class; none for an interface. */
    public List<IType> subclasses() throws CoreException {
        return type.isInterface() ? List.of() : subtypes();
    }

    /** The direct subtypes, by file and position. */
    public List<IType> subtypes() throws CoreException {
        computeSubtypes();
        return subtypes;
    }

    /** Every subtype, direct or not, by file and position. */
    public List<IType> allSubtypes() throws CoreException {
        computeSubtypes();
        return allSubtypes;
    }

    /**
     * The method {@code method}, declared in this hierarchy's type, overrides, as
     * {@code Bindings.findOverriddenMethod} finds it; null when it overrides none.
     */
    public IMethod overridden(IMethod method) throws CoreException {
        requireDeclared(method);
        IMethod[] overridden = { null };
        CoreException[] failure = { null };
        if (method.isBinary()) {
            ModelElements.parse(project, List.of(), List.of(type.getKey()), (unit, ast) -> { }, (key, binding) -> {
                try {
                    ModelElements elements = new ModelElements(null, null);
                    IMethodBinding declaration = binding instanceof ITypeBinding typeBinding
                        ? declaredBinding(typeBinding, method, elements) : null;
                    overridden[0] = overriddenOf(required(declaration, method), elements);
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        } else {
            ICompilationUnit unit = method.getCompilationUnit();
            ModelElements.parse(project, List.of(unit), List.of(), (source, ast) -> {
                try {
                    ModelElements elements = new ModelElements(source, ast);
                    overridden[0] = overriddenOf(required(elements.declarationOf(method), method), elements);
                } catch (CoreException e) {
                    failure[0] = e;
                }
            }, (key, binding) -> { });
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        return overridden[0];
    }

    /** {@code binding}, the compiler's binding of {@code method}, which must have one. */
    private static IMethodBinding required(IMethodBinding binding, IMethod method) throws JavaModelException {
        if (binding == null) {
            throw new JavaModelException(new IllegalStateException("The compiler resolves no binding for "
                + method.getDeclaringType().getFullyQualifiedName() + "#" + method.getElementName()),
                IJavaModelStatusConstants.CORE_EXCEPTION);
        }
        return binding;
    }

    /**
     * Every method of a subtype that overrides {@code method}, declared in this hierarchy's type,
     * by file and position. A constructor, a static and a private method are overridden by none.
     */
    public List<IMethod> overriding(IMethod method) throws CoreException {
        requireDeclared(method);
        int flags = method.getFlags();
        if (method.isConstructor() || Flags.isStatic(flags) || Flags.isPrivate(flags)) {
            return List.of();
        }
        // An overrider has the method's name and number of parameters (JLS 8.4.2): only the
        // subtypes declaring such a method are read with bindings.
        Map<ICompilationUnit, Set<IType>> sources = new LinkedHashMap<>();
        Map<String, IType> classFiles = new LinkedHashMap<>();
        for (IType subtype : allSubtypes()) {
            if (declaresLike(subtype, method)) {
                if (subtype.isBinary()) {
                    classFiles.put(subtype.getKey(), subtype);
                } else {
                    sources.computeIfAbsent(subtype.getCompilationUnit(), unit -> new HashSet<>()).add(subtype);
                }
            }
        }
        List<IMethod> overriders = new ArrayList<>();
        CoreException[] failure = { null };
        ModelElements.parse(project, sources.keySet(), classFiles.keySet(), (unit, ast) -> {
            ModelElements elements = new ModelElements(unit, ast);
            Set<IType> candidates = sources.get(unit);
            ModelElements.forEachTypeDeclaration(ast, node -> {
                try {
                    if (failure[0] == null && candidates.contains(elements.typeAt(node))) {
                        addOverriders(ModelElements.bindingOf(node), method, elements, overriders);
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        }, (key, binding) -> {
            try {
                if (failure[0] == null && binding instanceof ITypeBinding subtype) {
                    addOverriders(subtype, method, new ModelElements(null, null), overriders);
                } else if (failure[0] == null) {
                    failure[0] = unresolved(classFiles.get(key));
                }
            } catch (CoreException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        overriders.sort(METHOD_ORDER);
        return List.copyOf(overriders);
    }

    // ---------------------------------------------------------------- supertypes

    private record Supertypes(IType superclass, List<IType> superInterfaces, List<IType> allSuperclasses,
                              List<IType> allSuperInterfaces, List<IType> allSupertypes) {
    }

    private Supertypes supertypesOf(IType declared) throws CoreException {
        Supertypes[] read = { null };
        CoreException[] failure = { null };
        if (declared.isBinary()) {
            ModelElements.parse(project, List.of(), List.of(declared.getKey()), (unit, ast) -> { }, (key, binding) -> {
                try {
                    if (binding instanceof ITypeBinding typeBinding) {
                        read[0] = supertypesOf(typeBinding, new ModelElements(null, null));
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        } else {
            ModelElements.parse(project, List.of(declared.getCompilationUnit()), List.of(), (unit, ast) -> {
                try {
                    ModelElements elements = new ModelElements(unit, ast);
                    ITypeBinding binding = elements.declarationOf(declared);
                    if (binding != null) {
                        read[0] = supertypesOf(binding, elements);
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            }, (key, binding) -> { });
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        if (read[0] == null) {
            throw unresolved(declared);
        }
        return read[0];
    }

    private static Supertypes supertypesOf(ITypeBinding binding, ModelElements elements) throws JavaModelException {
        IType superclass = binding.getSuperclass() == null ? null : elements.typeOf(binding.getSuperclass());
        List<IType> superInterfaces = new ArrayList<>();
        for (ITypeBinding superInterface : binding.getInterfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null) {
                superInterfaces.add(element);
            }
        }
        Set<IType> allSuperclasses = new LinkedHashSet<>();
        for (ITypeBinding current = binding.getSuperclass(); current != null; current = current.getSuperclass()) {
            IType element = elements.typeOf(current);
            if (element != null) {
                allSuperclasses.add(element);
            }
        }
        Set<IType> allSuperInterfaces = new LinkedHashSet<>();
        addSuperInterfaces(binding, elements, allSuperInterfaces);
        Set<IType> allSupertypes = new LinkedHashSet<>();
        addSupertypes(binding, elements, allSupertypes);
        return new Supertypes(superclass, List.copyOf(superInterfaces), List.copyOf(allSuperclasses),
            List.copyOf(allSuperInterfaces), List.copyOf(allSupertypes));
    }

    /** TypeHierarchy.getAllSuperInterfaces0's order: each interface, then its own; then the superclass's. */
    private static void addSuperInterfaces(ITypeBinding binding, ModelElements elements, Set<IType> into)
            throws JavaModelException {
        for (ITypeBinding superInterface : binding.getInterfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null && into.add(element)) {
                addSuperInterfaces(superInterface, elements, into);
            }
        }
        if (binding.getSuperclass() != null) {
            addSuperInterfaces(binding.getSuperclass(), elements, into);
        }
    }

    /** TypeHierarchy.getAllSupertypes0's order: the interfaces as above, then the superclass and its supertypes. */
    private static void addSupertypes(ITypeBinding binding, ModelElements elements, Set<IType> into)
            throws JavaModelException {
        for (ITypeBinding superInterface : binding.getInterfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null && into.add(element)) {
                addSuperInterfaces(superInterface, elements, into);
            }
        }
        ITypeBinding superclass = binding.getSuperclass();
        if (superclass != null) {
            IType element = elements.typeOf(superclass);
            if (element != null) {
                into.add(element);
            }
            addSupertypes(superclass, elements, into);
        }
    }

    // ---------------------------------------------------------------- subtypes

    private void computeSubtypes() throws CoreException {
        if (subtypes != null) {
            return;
        }
        if (type.isAnonymous()) {
            // No class can name an anonymous class: it has no subtype.
            subtypes = List.of();
            allSubtypes = List.of();
            return;
        }
        if (isImplicitRoot(type)) {
            RootSubtypes root = rootSubtypes(type);
            subtypes = sorted(root.direct());
            allSubtypes = sorted(root.all());
            return;
        }
        Set<IType> direct = directSubtypes(List.of(type));
        Set<IType> all = new LinkedHashSet<>(direct);
        List<IType> frontier = new ArrayList<>(direct);
        while (!frontier.isEmpty()) {
            List<IType> searched = new ArrayList<>();
            for (IType subtype : frontier) {
                if (isImplicitRoot(subtype)) {
                    // Reached through an interface it implements (Enum through Comparable): its own
                    // subtypes are complete, roots included, so none of them is searched again.
                    all.addAll(rootSubtypes(subtype).all());
                } else if (!subtype.isAnonymous()) {
                    searched.add(subtype);
                }
            }
            List<IType> next = new ArrayList<>();
            for (IType found : directSubtypes(searched)) {
                if (all.add(found)) {
                    next.add(found);
                }
            }
            frontier = next;
        }
        subtypes = sorted(direct);
        allSubtypes = sorted(all);
    }

    /**
     * The types naming one of {@code supertypes}, none an implicit root or anonymous, as their
     * superclass or interface, with the constant bodies of those that are source enums.
     */
    private Set<IType> directSubtypes(Collection<IType> supertypes) throws CoreException {
        Set<IType> found = new LinkedHashSet<>();
        SearchPattern pattern = null;
        Map<ICompilationUnit, Set<IType>> local = new LinkedHashMap<>();
        for (IType supertype : supertypes) {
            found.addAll(constantBodies(supertype));
            if (!supertype.isBinary() && ModelElements.isInLocalScope(supertype)) {
                local.computeIfAbsent(supertype.getCompilationUnit(), unit -> new HashSet<>()).add(supertype);
                continue;
            }
            SearchPattern implementors = SearchPattern.createPattern(supertype, IJavaSearchConstants.IMPLEMENTORS);
            if (implementors != null) {
                pattern = pattern == null ? implementors : SearchPattern.createOrPattern(pattern, implementors);
            }
        }
        found.addAll(localSubtypes(local));
        if (pattern == null) {
            return found;
        }
        engine.search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() }, scope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) throws CoreException {
                    if (match.getAccuracy() == SearchMatch.A_ACCURATE
                            && SearchService.withModelElement(match).getElement() instanceof IType subtype
                            && !subtype.isLambda() && (!subtype.isBinary() || isResolvedTo(subtype))) {
                        found.add(subtype);
                    }
                }
            }, new NullProgressMonitor());
        return found;
    }

    /**
     * Whether the project's classpath resolves the name of {@code binary}, a class-file type, to
     * it: a copy of a type that an earlier entry also declares, such as a module's own sources
     * and its installed jar, is shadowed and denotes no type of its own (the compiler and
     * {@code IJavaProject.findType} take the first). Decided by its top-level type.
     */
    private boolean isResolvedTo(IType binary) throws JavaModelException {
        IType topLevel = binary;
        while (topLevel.getDeclaringType() != null) {
            topLevel = topLevel.getDeclaringType();
        }
        IType resolved = project.findType(topLevel.getFullyQualifiedName());
        return resolved == null || resolved.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT)
            .equals(binary.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT));
    }

    /**
     * The types naming one of {@code supertypes}, local classes by unit, as their superclass or
     * interface. A local class is named only within its own unit, so its subtypes are read from
     * the bindings of that unit's type declarations.
     */
    private Set<IType> localSubtypes(Map<ICompilationUnit, Set<IType>> supertypes) throws CoreException {
        Set<IType> found = new LinkedHashSet<>();
        CoreException[] failure = { null };
        ModelElements.parse(project, supertypes.keySet(), List.of(), (unit, ast) -> {
            ModelElements elements = new ModelElements(unit, ast);
            Set<IType> named = supertypes.get(unit);
            ModelElements.forEachTypeDeclaration(ast, node -> {
                try {
                    ITypeBinding binding = failure[0] == null ? ModelElements.bindingOf(node) : null;
                    if (binding != null && namesAny(binding, named, elements)) {
                        IType subtype = elements.typeAt(node);
                        if (subtype != null) {
                            found.add(subtype);
                        }
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        }, (key, binding) -> { });
        if (failure[0] != null) {
            throw failure[0];
        }
        return found;
    }

    /** Whether the superclass or an interface of {@code binding} is one of {@code supertypes}. */
    private static boolean namesAny(ITypeBinding binding, Set<IType> supertypes, ModelElements elements)
            throws JavaModelException {
        if (binding.getSuperclass() != null && supertypes.contains(elements.typeOf(binding.getSuperclass()))) {
            return true;
        }
        for (ITypeBinding superInterface : binding.getInterfaces()) {
            if (supertypes.contains(elements.typeOf(superInterface))) {
                return true;
            }
        }
        return false;
    }

    /** The bodies of the constants of {@code type} when it is an enum with source. */
    private static List<IType> constantBodies(IType type) throws JavaModelException {
        if (type.isBinary() || !type.isEnum()) {
            return List.of();
        }
        List<IType> bodies = new ArrayList<>();
        for (IField field : type.getFields()) {
            if (field.isEnumConstant()) {
                for (IJavaElement child : field.getChildren()) {
                    if (child instanceof IType body) {
                        bodies.add(body);
                    }
                }
            }
        }
        return bodies;
    }

    private record RootSubtypes(Set<IType> direct, Set<IType> all) {
    }

    /**
     * The subtypes of an implicit root: the class-file ones of JDT's hierarchy, and every type the
     * project's source units declare, named, local or anonymous, whose supertypes, read from its
     * binding, include it.
     *
     * <p>JDT's hierarchy is not used for source types: it collects candidate files from the search
     * index by supertype name (IndexBasedHierarchyBuilder.searchAllPossibleSubTypes, :470-580), and
     * the index names no implicit superclass. A record is indexed with the superclass Object
     * (SourceIndexerRequestor.enterRecord :241-243 through enterClass, with no superclass;
     * AbstractIndexer.addClassDeclaration :62-69; SuperTypeReferencePattern.createIndexKey
     * :56-57), so a source record joins JDT's hierarchy of java.lang.Record only when another of
     * its file's index entries matches a name the walk queued, the JDK's records among them, which
     * depends on what the index holds when it is asked. A class file records its real superclass,
     * so its subtypes are read from the index as they are.
     */
    private RootSubtypes rootSubtypes(IType root) throws CoreException {
        ITypeHierarchy hierarchy = root.newTypeHierarchy(project, new NullProgressMonitor());
        Set<IType> direct = new LinkedHashSet<>(classFileTypes(hierarchy.getSubtypes(root)));
        Set<IType> all = new LinkedHashSet<>(classFileTypes(hierarchy.getAllSubtypes(root)));

        CoreException[] failure = { null };
        ModelElements.parse(project, sourceUnits(), List.of(), (unit, ast) -> {
            ModelElements elements = new ModelElements(unit, ast);
            ModelElements.forEachTypeDeclaration(ast, node -> {
                try {
                    IType declared = failure[0] == null ? elements.typeAt(node) : null;
                    if (declared != null) {
                        ITypeBinding binding = ModelElements.bindingOf(node);
                        if (binding == null) {
                            throw unresolved(declared);
                        }
                        Supertypes supertypes = supertypesOf(binding, elements);
                        if (root.equals(supertypes.superclass()) || supertypes.superInterfaces().contains(root)) {
                            direct.add(declared);
                        }
                        if (supertypes.allSupertypes().contains(root)) {
                            all.add(declared);
                        }
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        }, (key, binding) -> { });
        if (failure[0] != null) {
            throw failure[0];
        }
        return new RootSubtypes(direct, all);
    }

    /**
     * The class-file types among {@code types} that the project's classpath resolves their names
     * to; source types and lambda expressions are left out.
     */
    private List<IType> classFileTypes(IType[] types) throws JavaModelException {
        List<IType> classFiles = new ArrayList<>();
        for (IType type : types) {
            if (type.isBinary() && !type.isLambda() && isResolvedTo(type)) {
                classFiles.add(type);
            }
        }
        return classFiles;
    }

    /** Every compilation unit of the project's source folders. */
    private List<ICompilationUnit> sourceUnits() throws JavaModelException {
        List<ICompilationUnit> units = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment fragment) {
                    units.addAll(List.of(fragment.getCompilationUnits()));
                }
            }
        }
        return units;
    }

    private static boolean isImplicitRoot(IType type) {
        return type.isBinary() && IMPLICIT_ROOTS.contains(type.getFullyQualifiedName());
    }

    // ---------------------------------------------------------------- methods

    /**
     * Adds every method {@code subtype} declares that overrides {@code method} as it inherits it.
     */
    private void addOverriders(ITypeBinding subtype, IMethod method, ModelElements elements, List<IMethod> into)
            throws CoreException {
        IMethodBinding inherited = required(inheritedBinding(subtype, method, elements), method);
        for (IMethodBinding candidate : subtype.getDeclaredMethods()) {
            if (candidate.overrides(inherited)) {
                IMethod overrider = elements.methodOf(candidate);
                if (overrider != null) {
                    into.add(overrider);
                }
            }
        }
    }

    /**
     * {@code method}'s binding as {@code subtype} inherits it, with the type arguments its
     * supertypes give: found on the supertype of {@code subtype} that is this hierarchy's type.
     */
    private IMethodBinding inheritedBinding(ITypeBinding subtype, IMethod method, ModelElements elements)
            throws CoreException {
        Deque<ITypeBinding> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        addSupertypeBindings(subtype, pending);
        while (!pending.isEmpty()) {
            ITypeBinding supertype = pending.removeFirst();
            if (!seen.add(supertype.getKey())) {
                continue;
            }
            if (type.equals(elements.typeOf(supertype))) {
                return declaredBinding(supertype, method, elements);
            }
            addSupertypeBindings(supertype, pending);
        }
        return null;
    }

    private static void addSupertypeBindings(ITypeBinding binding, Deque<ITypeBinding> into) {
        if (binding.getSuperclass() != null) {
            into.add(binding.getSuperclass());
        }
        into.addAll(List.of(binding.getInterfaces()));
    }

    /** The binding among those {@code declaring} declares whose model element is {@code method}. */
    private static IMethodBinding declaredBinding(ITypeBinding declaring, IMethod method, ModelElements elements)
            throws CoreException {
        for (IMethodBinding declared : declaring.getDeclaredMethods()) {
            if (method.equals(elements.methodOf(declared))) {
                return declared;
            }
        }
        return null;
    }

    /** The model element of the method {@code declaration} overrides, or null. */
    private static IMethod overriddenOf(IMethodBinding declaration, ModelElements elements) throws CoreException {
        IMethodBinding overridden = declaration == null ? null : Bindings.findOverriddenMethod(declaration, true);
        return overridden == null ? null : elements.methodOf(overridden);
    }

    /** Whether {@code type} declares a method of {@code method}'s name and number of parameters. */
    private static boolean declaresLike(IType type, IMethod method) throws JavaModelException {
        for (IMethod declared : type.getMethods()) {
            if (declared.getElementName().equals(method.getElementName())
                    && declared.getNumberOfParameters() == method.getNumberOfParameters()) {
                return true;
            }
        }
        return false;
    }

    private void requireDeclared(IMethod method) {
        if (!type.equals(method.getDeclaringType())) {
            throw new IllegalArgumentException(method.getElementName() + " is not declared in "
                + type.getFullyQualifiedName());
        }
    }

    private static CoreException unresolved(IType type) {
        return new JavaModelException(new IllegalStateException(
            "The compiler resolves no binding for " + type.getFullyQualifiedName()),
            IJavaModelStatusConstants.CORE_EXCEPTION);
    }

    // ---------------------------------------------------------------- order

    private static List<IType> sorted(Collection<IType> types) {
        List<IType> sorted = new ArrayList<>(types);
        sorted.sort(TYPE_ORDER);
        return List.copyOf(sorted);
    }

    private static int compareTypes(IType a, IType b) {
        if (a.isBinary() != b.isBinary()) {
            return a.isBinary() ? 1 : -1;
        }
        if (a.isBinary()) {
            return a.getFullyQualifiedName().compareTo(b.getFullyQualifiedName());
        }
        int byFile = compareFiles(a, b);
        return byFile != 0 ? byFile : Integer.compare(start(a), start(b));
    }

    private static int compareMethods(IMethod a, IMethod b) {
        int byType = compareTypes(a.getDeclaringType(), b.getDeclaringType());
        if (byType != 0) {
            return byType;
        }
        if (a.isBinary()) {
            int byName = a.getElementName().compareTo(b.getElementName());
            return byName != 0 ? byName : String.join(",", a.getParameterTypes()).compareTo(String.join(",", b.getParameterTypes()));
        }
        return Integer.compare(start(a), start(b));
    }

    /** By the files' locations, segment by segment, a shorter path before its extensions. */
    private static int compareFiles(IJavaElement a, IJavaElement b) {
        IPath left = location(a);
        IPath right = location(b);
        int segments = Math.min(left.segmentCount(), right.segmentCount());
        for (int i = 0; i < segments; i++) {
            int bySegment = left.segment(i).compareTo(right.segment(i));
            if (bySegment != 0) {
                return bySegment;
            }
        }
        return Integer.compare(left.segmentCount(), right.segmentCount());
    }

    private static IPath location(IJavaElement element) {
        return element.getResource() != null && element.getResource().getLocation() != null
            ? element.getResource().getLocation()
            : element.getPath();
    }

    private static int start(ISourceReference element) {
        try {
            return element.getSourceRange().getOffset();
        } catch (JavaModelException e) {
            throw new IllegalStateException("No source range for " + element, e);
        }
    }
}
