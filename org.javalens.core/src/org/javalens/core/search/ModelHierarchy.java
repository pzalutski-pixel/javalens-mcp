package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
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
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.javalens.core.Deadline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
 * element ({@link ModelElements}).
 *
 * <p>A supertype the compiler cannot resolve, such as a type of a missing dependency, has no
 * binding: the binding of the type naming it holds no superclass, or java.lang.Object, in its place
 * and leaves it out of its interfaces (DefaultBindingResolver.getTypeBinding answers no binding for
 * a type not found, and TypeBinding.getInterfaces drops it). Each is found by comparing the names
 * the type's declaration writes ({@code IType.getSuperclassName}, {@code getSuperInterfaceNames}:
 * as written for a source type, qualified for a class file, the name after {@code new} for an
 * anonymous class) with the supertypes its binding holds, by simple name without type arguments,
 * and is reported by that name with the type that names it ({@link #unresolvedSupertypes}), for the
 * type and every supertype it reaches. The superclasses then run to the last that resolves and end
 * with java.lang.Object, the superclass of every class (JLS 8.1.4); the type's own superclass is
 * none when its declaration names one that does not resolve.
 *
 * <h2>Subtypes</h2>
 * A direct subtype names the type as its superclass or one of its interfaces. They are found by an
 * {@code IMPLEMENTORS} search for the type, which reports every class or interface naming it in
 * its {@code extends} or {@code implements} clause and every anonymous class created from it
 * (SuperTypeReferenceLocator), each match moved to the model's element at the match position
 * ({@link SearchService#withModelElement}); a lambda it reports is left out, and so is a class-file
 * copy of a type an earlier classpath entry declares (a module's installed jar beside its sources),
 * which the project's name lookup never resolves to. A match the search reports but cannot confirm
 * (A_INACCURATE: an extends or implements clause naming the type by a name the compiler cannot
 * resolve, SuperTypeReferenceLocator.resolveLevel(ASTNode) :115) is no subtype: it is kept as a
 * potential subtype ({@link #potentialSubtypes}) and searched no further.
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
 * method's package. {@link #allOverridden} is every method the given method overrides, in the
 * superclass chain and the interfaces alike. {@link #joinedInSubtypes} is every method a subtype
 * inherits together with the given method as one method, though neither overrides the other. A
 * method the compiler resolves no binding for is refused with a JavaModelException, never
 * answered as overriding nothing.
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
    private final List<UnresolvedSupertype> unresolvedSupertypes;

    private List<IType> subtypes;
    private List<IType> allSubtypes;
    private List<SearchMatch> potentialSubtypes;

    /** java.lang.Object as the project resolves it, once looked up. */
    private IType object;

    /**
     * A supertype the compiler cannot resolve: its {@code name} as the declaration of
     * {@code declaringType} writes it (type arguments included), and that type, this hierarchy's
     * type or one of its supertypes.
     */
    public record UnresolvedSupertype(String name, IType declaringType) {
    }

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
        this.unresolvedSupertypes = supertypes.unresolved();
    }

    /** The type this hierarchy is of. */
    public IType type() {
        return type;
    }

    /**
     * The direct superclass, or null for an interface, an annotation type and java.lang.Object, and
     * when the declaration names a superclass the compiler cannot resolve.
     */
    public IType superclass() {
        return superclass;
    }

    /** The direct interfaces, in declaration order. */
    public List<IType> superInterfaces() {
        return superInterfaces;
    }

    /**
     * Every superclass, from the nearest; when one does not resolve, those before it, then
     * java.lang.Object.
     */
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

    /**
     * The supertypes the compiler cannot resolve that this type's declaration names, then those
     * each of its supertypes names, in the order of {@link #allSupertypes}; within one declaration,
     * the superclass, then the interfaces in declaration order. Empty when every supertype resolves.
     */
    public List<UnresolvedSupertype> unresolvedSupertypes() {
        return unresolvedSupertypes;
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
     * The places the search for this type's subtypes, or those of a subtype, reports but cannot
     * confirm: an extends or implements clause naming one of them by a name the compiler cannot
     * resolve, so the class declaring it may be a subtype or not. None is among the subtypes. By
     * file and position.
     */
    public List<SearchMatch> potentialSubtypes() throws CoreException {
        computeSubtypes();
        return potentialSubtypes;
    }

    /**
     * The method {@code method}, declared in this hierarchy's type, overrides, as
     * {@code Bindings.findOverriddenMethod} finds it; null when it overrides none.
     */
    public IMethod overridden(IMethod method) throws CoreException {
        requireDeclared(method);
        IMethod[] overridden = { null };
        readDeclaration(method, (declaration, elements) -> overridden[0] = overriddenOf(declaration, elements));
        return overridden[0];
    }

    /** Reads the compiler's binding of {@code method}, declared in this hierarchy's type. */
    private interface DeclarationReader {
        void read(IMethodBinding declaration, ModelElements elements) throws CoreException;
    }

    /**
     * Gives {@code reader} the compiler's binding of {@code method}, declared in this hierarchy's
     * type, read from its class file or its compilation unit; a method the compiler resolves no
     * binding for is refused with a JavaModelException.
     */
    private void readDeclaration(IMethod method, DeclarationReader reader) throws CoreException {
        CoreException[] failure = { null };
        if (method.isBinary()) {
            ModelElements.parse(project, List.of(), List.of(type.getKey()), (unit, ast) -> { }, (key, binding) -> {
                try {
                    ModelElements elements = new ModelElements(null, null);
                    IMethodBinding declaration = binding instanceof ITypeBinding typeBinding
                        ? declaredBinding(typeBinding, method, elements) : null;
                    reader.read(required(declaration, method), elements);
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        } else {
            ICompilationUnit unit = method.getCompilationUnit();
            ModelElements.parse(project, List.of(unit), List.of(), (source, ast) -> {
                try {
                    ModelElements elements = new ModelElements(source, ast);
                    reader.read(required(elements.declarationOf(method), method), elements);
                } catch (CoreException e) {
                    failure[0] = e;
                }
            }, (key, binding) -> { });
        }
        if (failure[0] != null) {
            throw failure[0];
        }
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

    /**
     * Every method {@code method}, declared in this hierarchy's type, overrides, as the compiler
     * decides it ({@code IMethodBinding.overrides}): the methods of every superclass and
     * interface, each reached with the type arguments the supertypes give, by file and position.
     * A constructor, a static and a private method override none.
     */
    public List<IMethod> allOverridden(IMethod method) throws CoreException {
        requireDeclared(method);
        int flags = method.getFlags();
        if (method.isConstructor() || Flags.isStatic(flags) || Flags.isPrivate(flags)) {
            return List.of();
        }
        Set<IMethod> overridden = new LinkedHashSet<>();
        readDeclaration(method, (declaration, elements) -> {
            Deque<ITypeBinding> pending = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            addSupertypeBindings(declaration.getDeclaringClass(), pending);
            while (!pending.isEmpty()) {
                ITypeBinding supertype = pending.removeFirst();
                if (!seen.add(supertype.getKey())) {
                    continue;
                }
                for (IMethodBinding candidate : supertype.getDeclaredMethods()) {
                    IMethod element = declaration.overrides(candidate) ? elements.methodOf(candidate) : null;
                    if (element != null) {
                        overridden.add(element);
                    }
                }
                addSupertypeBindings(supertype, pending);
            }
        });
        return sortedMethods(overridden);
    }

    /**
     * Every method that a subtype of this hierarchy's type inherits together with {@code method},
     * declared in this type, as one method, though neither overrides the other: as when a class
     * inherits {@code method} from its superclass and with it implements a method of an interface
     * it names, declaring neither (JLS 8.4.8.1, 8.4.8.4), the pairs JDT's rename finds as "married"
     * types (RippleMethodFinder2.getAllRippleMethods :274-330). Within the subtype both are
     * inherited, neither static nor private, and have the same name and, as the subtype sees them
     * with the type arguments its supertypes give, the same erasure of parameter types: inherited
     * methods of the same erasure are override-equivalent in a type that compiles (JLS 8.4.2,
     * 8.4.8.3). The erasure is compared, not {@code IMethodBinding.isSubsignature}, which reads a
     * method of a parameterized type as its generic declaration (MethodVerifier15.isMethodSubsignature
     * :956-966) and so misses {@code Peg<T>.hang(T)} implementing {@code Hook.hang(String)} in a
     * {@code Peg<String>}. By file and position; none for a constructor, a static and a private
     * method.
     */
    public List<IMethod> joinedInSubtypes(IMethod method) throws CoreException {
        requireDeclared(method);
        int flags = method.getFlags();
        if (method.isConstructor() || Flags.isStatic(flags) || Flags.isPrivate(flags)) {
            return List.of();
        }
        Map<ICompilationUnit, Set<IType>> sources = new LinkedHashMap<>();
        Map<String, IType> classFiles = new LinkedHashMap<>();
        for (IType subtype : allSubtypes()) {
            if (subtype.isBinary()) {
                classFiles.put(subtype.getKey(), subtype);
            } else {
                sources.computeIfAbsent(subtype.getCompilationUnit(), unit -> new HashSet<>()).add(subtype);
            }
        }
        Set<IMethod> joined = new LinkedHashSet<>();
        CoreException[] failure = { null };
        ModelElements.parse(project, sources.keySet(), classFiles.keySet(), (unit, ast) -> {
            ModelElements elements = new ModelElements(unit, ast);
            Set<IType> candidates = sources.get(unit);
            ModelElements.forEachTypeDeclaration(ast, node -> {
                try {
                    IType subtype = failure[0] == null ? elements.typeAt(node) : null;
                    if (subtype != null && candidates.contains(subtype)) {
                        ITypeBinding binding = ModelElements.bindingOf(node);
                        if (binding == null) {
                            throw unresolved(subtype);
                        }
                        addJoined(binding, method, elements, joined);
                    }
                } catch (CoreException e) {
                    failure[0] = e;
                }
            });
        }, (key, binding) -> {
            try {
                if (failure[0] == null && binding instanceof ITypeBinding subtype) {
                    addJoined(subtype, method, new ModelElements(null, null), joined);
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
        joined.remove(method);
        return sortedMethods(joined);
    }

    // ---------------------------------------------------------------- supertypes

    private record Supertypes(IType superclass, List<IType> superInterfaces, List<IType> allSuperclasses,
                              List<IType> allSuperInterfaces, List<IType> allSupertypes,
                              List<UnresolvedSupertype> unresolved) {
    }

    /**
     * The direct supertypes of one type as its binding holds them, and those its declaration names
     * that do not resolve: {@code superclass} is null when it names an unresolved superclass
     * ({@code superclassUnresolved}), whatever the binding holds in its place.
     */
    private record Declared(ITypeBinding superclass, boolean superclassUnresolved, List<ITypeBinding> interfaces,
                            List<String> unresolved) {
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

    private Supertypes supertypesOf(ITypeBinding binding, ModelElements elements) throws JavaModelException {
        Map<String, Declared> declarations = new HashMap<>();
        Declared own = declared(binding, elements, declarations);
        IType superclass = own.superclass() == null ? null : elements.typeOf(own.superclass());
        List<IType> superInterfaces = new ArrayList<>();
        for (ITypeBinding superInterface : own.interfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null) {
                superInterfaces.add(element);
            }
        }
        Set<IType> allSuperclasses = new LinkedHashSet<>();
        Declared current = own;
        while (current.superclass() != null) {
            IType element = elements.typeOf(current.superclass());
            if (element != null) {
                allSuperclasses.add(element);
            }
            current = declared(current.superclass(), elements, declarations);
        }
        Map<IType, ITypeBinding> allSuperInterfaces = new LinkedHashMap<>();
        addSuperInterfaces(own, elements, declarations, allSuperInterfaces);
        Map<IType, ITypeBinding> allSupertypes = new LinkedHashMap<>();
        addSupertypes(own, elements, declarations, allSupertypes);

        List<UnresolvedSupertype> unresolved = new ArrayList<>();
        IType declaring = elements.typeOf(binding);
        for (String name : own.unresolved()) {
            unresolved.add(new UnresolvedSupertype(name, declaring));
        }
        for (Map.Entry<IType, ITypeBinding> supertype : allSupertypes.entrySet()) {
            for (String name : declared(supertype.getValue(), elements, declarations).unresolved()) {
                unresolved.add(new UnresolvedSupertype(name, supertype.getKey()));
            }
        }
        List<IType> supertypes = new ArrayList<>(allSupertypes.keySet());
        // Every class extends java.lang.Object (JLS 8.1.4), past a superclass that does not resolve too.
        if (current.superclassUnresolved() && object() != null) {
            allSuperclasses.add(object());
            if (!supertypes.contains(object())) {
                supertypes.add(object());
            }
        }
        return new Supertypes(superclass, List.copyOf(superInterfaces), List.copyOf(allSuperclasses),
            List.copyOf(allSuperInterfaces.keySet()), List.copyOf(supertypes), List.copyOf(unresolved));
    }

    /** java.lang.Object as the project resolves it, or null when it resolves none. */
    private IType object() throws JavaModelException {
        if (object == null) {
            object = project.findType("java.lang.Object");
        }
        return object;
    }

    /**
     * The direct supertypes of {@code binding}: those its binding holds, and the names its
     * declaration writes that none of them answers (the class comment gives the comparison). An
     * interface or annotation type has no superclass to compare: its model reports java.lang.Object
     * as the superclass of an interface (IType.getSuperclassName). Read once per type and kept in
     * {@code declarations} by binding key.
     */
    private static Declared declared(ITypeBinding binding, ModelElements elements, Map<String, Declared> declarations)
            throws JavaModelException {
        Declared known = declarations.get(binding.getKey());
        if (known != null) {
            return known;
        }
        ITypeBinding superclass = binding.getSuperclass();
        List<ITypeBinding> interfaces = List.of(binding.getInterfaces());
        IType element = elements.typeOf(binding);
        Declared declared;
        if (element == null) {
            declared = new Declared(superclass, false, interfaces, List.of());
        } else if (element.isAnonymous()) {
            // The name after `new` is the class extended or the interface implemented.
            String written = element.getSuperclassName();
            boolean resolved = written == null || names(written, superclass)
                || interfaces.stream().anyMatch(superInterface -> names(written, superInterface));
            declared = resolved
                ? new Declared(superclass, false, interfaces, List.of())
                : new Declared(null, true, interfaces, List.of(written));
        } else {
            List<String> unresolved = new ArrayList<>();
            boolean superclassUnresolved = false;
            if (!element.isInterface()) {
                String written = element.getSuperclassName();
                if (written != null && !names(written, superclass)) {
                    unresolved.add(written);
                    superclassUnresolved = true;
                }
            }
            List<ITypeBinding> unmatched = new ArrayList<>(interfaces);
            for (String written : element.getSuperInterfaceNames()) {
                ITypeBinding match = unmatched.stream().filter(superInterface -> names(written, superInterface))
                    .findFirst().orElse(null);
                if (match == null) {
                    unresolved.add(written);
                } else {
                    unmatched.remove(match);
                }
            }
            declared = new Declared(superclassUnresolved ? null : superclass, superclassUnresolved, interfaces,
                List.copyOf(unresolved));
        }
        declarations.put(binding.getKey(), declared);
        return declared;
    }

    /**
     * Whether {@code written}, a supertype's name as a declaration writes it, names
     * {@code binding}: the same simple name, type arguments and qualification aside.
     */
    private static boolean names(String written, ITypeBinding binding) {
        if (binding == null) {
            return false;
        }
        int arguments = written.indexOf('<');
        String raw = (arguments < 0 ? written : written.substring(0, arguments)).trim();
        String simple = raw.substring(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('$')) + 1);
        return simple.equals(binding.getErasure().getName());
    }

    /** TypeHierarchy.getAllSuperInterfaces0's order: each interface, then its own; then the superclass's. */
    private static void addSuperInterfaces(Declared declared, ModelElements elements,
                                           Map<String, Declared> declarations, Map<IType, ITypeBinding> into)
            throws JavaModelException {
        for (ITypeBinding superInterface : declared.interfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null && into.putIfAbsent(element, superInterface) == null) {
                addSuperInterfaces(declared(superInterface, elements, declarations), elements, declarations, into);
            }
        }
        if (declared.superclass() != null) {
            addSuperInterfaces(declared(declared.superclass(), elements, declarations), elements, declarations, into);
        }
    }

    /** TypeHierarchy.getAllSupertypes0's order: the interfaces as above, then the superclass and its supertypes. */
    private static void addSupertypes(Declared declared, ModelElements elements, Map<String, Declared> declarations,
                                      Map<IType, ITypeBinding> into) throws JavaModelException {
        for (ITypeBinding superInterface : declared.interfaces()) {
            IType element = elements.typeOf(superInterface);
            if (element != null && into.putIfAbsent(element, superInterface) == null) {
                addSuperInterfaces(declared(superInterface, elements, declarations), elements, declarations, into);
            }
        }
        ITypeBinding superclass = declared.superclass();
        if (superclass != null) {
            IType element = elements.typeOf(superclass);
            if (element != null) {
                into.putIfAbsent(element, superclass);
            }
            addSupertypes(declared(superclass, elements, declarations), elements, declarations, into);
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
            potentialSubtypes = List.of();
            return;
        }
        if (isImplicitRoot(type)) {
            // Read from bindings and class files, never from a search match: nothing to confirm.
            RootSubtypes root = rootSubtypes(type);
            subtypes = sorted(root.direct());
            allSubtypes = sorted(root.all());
            potentialSubtypes = List.of();
            return;
        }
        List<SearchMatch> potential = new ArrayList<>();
        Set<IType> direct = directSubtypes(List.of(type), potential);
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
            for (IType found : directSubtypes(searched, potential)) {
                if (all.add(found)) {
                    next.add(found);
                }
            }
            frontier = next;
        }
        subtypes = sorted(direct);
        allSubtypes = sorted(all);
        potential.sort(MATCH_ORDER);
        potentialSubtypes = List.copyOf(potential);
    }

    /** Matches by their file's path, then by offset. */
    private static final Comparator<SearchMatch> MATCH_ORDER = Comparator
        .comparing((SearchMatch match) -> match.getResource() == null ? "" : match.getResource().getFullPath().toString())
        .thenComparingInt(SearchMatch::getOffset);

    /**
     * The types naming one of {@code supertypes}, none an implicit root or anonymous, as their
     * superclass or interface, with the constant bodies of those that are source enums. A match
     * the search reports but cannot confirm goes to {@code potential} instead, unless it is a lambda
     * expression, which is no type.
     */
    private Set<IType> directSubtypes(Collection<IType> supertypes, List<SearchMatch> potential) throws CoreException {
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
                    Object element = SearchService.withModelElement(match).getElement();
                    if (element instanceof IType subtype && subtype.isLambda()) {
                        return;
                    }
                    if (match.getAccuracy() == SearchMatch.A_INACCURATE) {
                        potential.add(match);
                    } else if (element instanceof IType subtype && (!subtype.isBinary() || isResolvedTo(subtype))) {
                        found.add(subtype);
                    }
                }
            }, Deadline.monitor());
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
        ITypeHierarchy hierarchy = root.newTypeHierarchy(project, Deadline.monitor());
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
     * Adds every method of a supertype of {@code subtype} that {@code subtype} inherits together
     * with {@code method} as one method, neither overriding the other ({@link #joinedInSubtypes}).
     */
    private void addJoined(ITypeBinding subtype, IMethod method, ModelElements elements, Set<IMethod> into)
            throws CoreException {
        IMethodBinding inherited = required(inheritedBinding(subtype, method, elements), method);
        if (!isInheritedBy(inherited, subtype)) {
            return;
        }
        Deque<ITypeBinding> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        addSupertypeBindings(subtype, pending);
        while (!pending.isEmpty()) {
            ITypeBinding supertype = pending.removeFirst();
            if (!seen.add(supertype.getKey())) {
                continue;
            }
            for (IMethodBinding candidate : supertype.getDeclaredMethods()) {
                IMethod element = isJoined(inherited, candidate, subtype) ? elements.methodOf(candidate) : null;
                if (element != null) {
                    into.add(element);
                }
            }
            addSupertypeBindings(supertype, pending);
        }
    }

    /**
     * Whether {@code subtype} inherits {@code candidate} with {@code inherited} as one method:
     * the same name and erasure of parameter types, neither a constructor, static or private,
     * and neither overriding the other.
     */
    private static boolean isJoined(IMethodBinding inherited, IMethodBinding candidate, ITypeBinding subtype) {
        int modifiers = candidate.getModifiers();
        return !candidate.isConstructor() && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)
            && candidate.getName().equals(inherited.getName())
            && isInheritedBy(candidate, subtype)
            && sameErasures(inherited.getParameterTypes(), candidate.getParameterTypes())
            && !inherited.overrides(candidate) && !candidate.overrides(inherited);
    }

    /**
     * Whether {@code subtype} can inherit {@code method}: a method of package access only within
     * its own package (JLS 8.4.8).
     */
    private static boolean isInheritedBy(IMethodBinding method, ITypeBinding subtype) {
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || method.getDeclaringClass().isInterface()) {
            return true;
        }
        return method.getDeclaringClass().getPackage().getKey().equals(subtype.getPackage().getKey());
    }

    private static boolean sameErasures(ITypeBinding[] left, ITypeBinding[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!left[i].getErasure().isEqualTo(right[i].getErasure())) {
                return false;
            }
        }
        return true;
    }

    private static List<IMethod> sortedMethods(Collection<IMethod> methods) {
        List<IMethod> sorted = new ArrayList<>(methods);
        sorted.sort(METHOD_ORDER);
        return List.copyOf(sorted);
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
