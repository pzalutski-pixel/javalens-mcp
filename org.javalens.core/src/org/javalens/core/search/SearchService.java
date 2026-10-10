package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.ReferenceMatch;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.javalens.core.Deadline;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wraps JDT SearchEngine for AI-optimized queries.
 * Provides fast, indexed search operations without file walking.
 *
 * <p>Key advantages over ASTParser approach:
 * <ul>
 *   <li>10x faster (uses pre-built index)</li>
 *   <li>Fine-grained search: read vs write access, cast references</li>
 *   <li>Inheritance-aware queries</li>
 *   <li>Cross-project search</li>
 * </ul>
 */
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /**
     * The {@code maxResults} that collects every match. Callers that filter, deduplicate or
     * rewrite the matches they receive pass it, so no match is dropped before they see it.
     */
    public static final int ALL_MATCHES = Integer.MAX_VALUE;

    private final IJavaProject project;
    private final SearchEngine engine;
    private final IJavaSearchScope scope;
    private final IJavaSearchScope sourceScope;

    public SearchService(IJavaProject project) {
        this.project = project;
        this.engine = new SearchEngine();
        this.scope = SearchEngine.createJavaSearchScope(new IJavaElement[]{ project });
        // Sources-only scope: used by fine-grain searches so common JDK types
        // (e.g. java.lang.String) don't pull every JDK match through the engine.
        this.sourceScope = SearchEngine.createJavaSearchScope(
            new IJavaElement[]{ project }, IJavaSearchScope.SOURCES);
        log.info("SearchService initialized for project: {}", project.getElementName());
    }

    /**
     * Search for declarations matching a pattern.
     *
     * @param pattern Glob pattern (e.g., "Order*", "*Service")
     * @param searchFor Type of element: TYPE, METHOD, FIELD, or null for all types
     * @param maxResults Maximum results to return
     * @return The first {@code maxResults} matching declarations and the count of all of them
     */
    public SearchResult searchSymbols(String pattern, Integer searchFor, int maxResults) throws CoreException {
        return searchSymbols(pattern, searchFor, element -> true, 0, maxResults);
    }

    /**
     * Search for declarations matching a pattern, keep those {@code accept} admits, and return
     * one page of them. Every match the search reports is checked against the pattern and
     * {@code accept} before any is counted or kept, so nothing caps the search ahead of its
     * filters.
     *
     * @param pattern Glob pattern: {@code *} matches any characters, {@code ?} exactly one
     * @param searchFor Type of element: TYPE, METHOD, FIELD, or null for all types
     * @param accept The declarations to keep among those matching the pattern
     * @param offset How many kept declarations, in search order, precede the page
     * @param limit The most declarations the page holds
     * @return The page ({@code matches}, kept declarations {@code offset} onward, at most
     *     {@code limit} of them) and the count of every kept declaration ({@code totalEncountered}).
     *     Declarations follow the page exactly when {@code offset + matches().size()} is less
     *     than that count. A declaration the search reports but cannot confirm is never kept (a
     *     pattern naming a declaring type makes it resolve each declaration, and one in a type
     *     whose supertypes do not all resolve may be in the named type's hierarchy: A_INACCURATE);
     *     the first {@code limit} of those that pass the filters are {@code potentialMatches},
     *     and {@code potentialTotal} counts them all.
     */
    public SearchResult searchSymbols(String pattern, Integer searchFor, Predicate<IJavaElement> accept,
                                      int offset, int limit) throws CoreException {
        // JDT's R_PATTERN_MATCH supports `*` natively but not `?`. The tool's contract
        // documents both as wildcards, so for patterns containing `?` we broaden the
        // JDT search (substituting `?` -> `*`) and then narrow client-side with a regex
        // compiled from the original glob.
        //
        // R_REGEXP_MATCH would be the natural alternative but the JDT Javadoc documents
        // it as "not yet supported by Eclipse JDT". JdtContractTest pins both behaviors:
        // patternMatch_questionWildcard_doesNotMatch confirms `?` is a literal under
        // R_PATTERN_MATCH; regexpMatch_anchoredLiteral_returnsEmpty confirms
        // R_REGEXP_MATCH returns zero matches. When the regexp rule is implemented
        // upstream, those tests fail and this shim can be deleted.
        int searchForType = searchFor != null ? searchFor : IJavaSearchConstants.TYPE;
        boolean hasQuestionMark = pattern.indexOf('?') >= 0;
        String jdtSearchPattern = hasQuestionMark ? pattern.replace('?', '*') : pattern;
        java.util.regex.Pattern clientFilter = hasQuestionMark
            ? java.util.regex.Pattern.compile(globToRegex(pattern))
            : null;

        SearchPattern jdtPattern = SearchPattern.createPattern(
            jdtSearchPattern,
            searchForType,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
        );

        if (jdtPattern == null) {
            log.warn("Invalid search pattern: {}", pattern);
            return new SearchResult(List.of(), 0);
        }

        // The `?` narrowing and `accept` run on every reported match before it is counted,
        // so the page and the count both describe the declarations that pass. A declaration
        // the search could not confirm is no declaration of the page: it is counted, and the
        // first `limit` of them kept, apart.
        List<SearchMatch> page = new ArrayList<>();
        int[] passing = { 0 };
        List<SearchMatch> potential = new ArrayList<>();
        int[] potentialTotal = { 0 };

        // Sources-only scope: symbol search returns the project's own declared symbols,
        // not classpath/JDK types or workspace-metadata phantoms (which leak unresolvable
        // absolute paths). Mirrors the fine-grain and reference-search scope discipline.
        engine.search(
            jdtPattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            sourceScope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    if (!(match.getElement() instanceof IJavaElement element)) {
                        return;
                    }
                    if (clientFilter != null && !clientFilter.matcher(element.getElementName()).matches()) {
                        return;
                    }
                    if (!accept.test(element)) {
                        return;
                    }
                    if (match.getAccuracy() == SearchMatch.A_INACCURATE) {
                        if (potential.size() < limit) {
                            potential.add(withModelElement(match));
                        }
                        potentialTotal[0]++;
                        return;
                    }
                    if (passing[0] >= offset && page.size() < limit) {
                        page.add(withModelElement(match));
                    }
                    passing[0]++;
                }
            },
            Deadline.monitor()
        );

        log.debug("Symbol search '{}' kept {} declarations, returning {} from offset {}",
            pattern, passing[0], page.size(), offset);
        return new SearchResult(page, passing[0], potential, potentialTotal[0]);
    }

    /**
     * Find all references to a Java element: the matches the search confirms, and apart from them
     * the places it reports but cannot confirm ({@link SearchResult#potentialMatches()}).
     *
     * <p>The references of an element of a class file, such as a JDK method, are the project's
     * own: they are searched in the project's source files only, as
     * {@link #findAllReferencesInSources} searches. The library's own uses of it, which a search of
     * the whole classpath reads from attached sources, are no references in the project and have
     * no location in it.
     *
     * @param element The element to find references to
     * @param limitTo Limit type: REFERENCES, READ_ACCESSES, WRITE_ACCESSES, or combination
     * @param maxResults Maximum results
     * @return List of reference locations
     */
    public SearchResult findReferences(IJavaElement element, int limitTo, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            element,
            limitTo
        );

        if (pattern == null) {
            log.warn("Cannot create pattern for element: {}", element);
            return new SearchResult(List.of(), 0);
        }

        boolean ofClassFile = isOfClassFile(element);
        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults, ofClassFile);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            ofClassFile ? sourceScope : scope,
            requestor,
            Deadline.monitor()
        );

        SearchResult result = requestor.toResult();
        log.debug("Reference search for {} found {} results (total encountered={}, potential={})",
            element.getElementName(), result.matches().size(), result.totalEncountered(), result.potentialTotal());

        // JDT's indexed reference search misses constructor delegations
        // (super(...)/this(...)) that are NOT the first statement of a constructor —
        // the shape introduced by JEP 513 flexible constructor bodies (Java 25).
        // Supplement the result with an AST scan so those call sites are reported by
        // every reference-based tool (find_references, change_method_signature, rename,
        // call hierarchy) the same as a first-statement delegation.
        if (element instanceof IMethod m && m.isConstructor()
                && (limitTo == IJavaSearchConstants.REFERENCES
                    || limitTo == IJavaSearchConstants.ALL_OCCURRENCES)) {
            return supplementConstructorDelegations(m, result, maxResults);
        }
        return result;
    }

    /** Whether {@code element} belongs to a class file: it lies in a binary package fragment root. */
    private static boolean isOfClassFile(IJavaElement element) throws JavaModelException {
        return element.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT) instanceof IPackageFragmentRoot root
            && root.getKind() == IPackageFragmentRoot.K_BINARY;
    }

    /** Whether {@code match} lies in one of the project's {@code .java} files. */
    private static boolean isInJavaFile(SearchMatch match) {
        return match.getResource() instanceof org.eclipse.core.resources.IFile f
            && "java".equalsIgnoreCase(f.getFileExtension());
    }

    /**
     * Add explicit {@code super(...)} / {@code this(...)} delegations that bind to
     * {@code constructor} but were not returned by the indexed search (JDT misses
     * the ones that are not the first statement of a flexible constructor body).
     * Delegations already present in {@code base} are not duplicated. A unit that cannot be
     * listed or read fails the search: the references would be short of its delegations.
     */
    private SearchResult supplementConstructorDelegations(IMethod constructor, SearchResult base, int maxResults)
            throws CoreException {
        ICompilationUnit[] units = collectSourceCompilationUnits();
        if (units.length == 0) {
            return base;
        }

        // Offsets the indexed search already reported, grouped by resource path, so
        // a delegation found by both routes is not counted twice. JDT's match offset
        // for a delegation falls inside the call node, so containment is the test.
        Map<String, List<Integer>> seen = new HashMap<>();
        for (SearchMatch existing : base.matches()) {
            if (existing.getResource() != null && existing.getResource().getLocation() != null) {
                seen.computeIfAbsent(existing.getResource().getLocation().toOSString(),
                    k -> new ArrayList<>()).add(existing.getOffset());
            }
        }

        String targetHandle = constructor.getHandleIdentifier();
        SearchParticipant participant = SearchEngine.getDefaultSearchParticipant();
        List<SearchMatch> added = new ArrayList<>();

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true);
        parser.setProject(project);
        parser.createASTs(units, new String[0], new ASTRequestor() {
            @Override
            public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
                ast.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(SuperConstructorInvocation node) {
                        consider(source, node, node.resolveConstructorBinding());
                        return true;
                    }
                    @Override
                    public boolean visit(ConstructorInvocation node) {
                        consider(source, node, node.resolveConstructorBinding());
                        return true;
                    }
                    private void consider(ICompilationUnit source, ASTNode node, IMethodBinding binding) {
                        if (binding == null) {
                            return;
                        }
                        IJavaElement bound = binding.getJavaElement();
                        if (bound == null || !targetHandle.equals(bound.getHandleIdentifier())) {
                            return;
                        }
                        int start = node.getStartPosition();
                        int length = node.getLength();
                        if (alreadySeen(source, start, length)) {
                            return;
                        }
                        added.add(new MethodReferenceMatch(
                            enclosingElement(source, node),
                            SearchMatch.A_ACCURATE, start, length,
                            false, participant, source.getResource()));
                    }
                    private boolean alreadySeen(ICompilationUnit source, int start, int length) {
                        if (source.getResource() == null || source.getResource().getLocation() == null) {
                            return false;
                        }
                        List<Integer> offsets = seen.get(source.getResource().getLocation().toOSString());
                        if (offsets == null) {
                            return false;
                        }
                        for (int off : offsets) {
                            if (off >= start && off <= start + length) {
                                return true;
                            }
                        }
                        return false;
                    }
                });
            }
        }, Deadline.monitor());

        if (added.isEmpty()) {
            return base;
        }

        List<SearchMatch> merged = new ArrayList<>(base.matches());
        for (SearchMatch m : added) {
            if (merged.size() >= maxResults) {
                break;
            }
            merged.add(m);
        }
        log.debug("Supplemented constructor {} with {} flexible-body delegation match(es)",
            constructor.getElementName(), added.size());
        return new SearchResult(merged, base.totalEncountered() + added.size(),
            base.potentialMatches(), base.potentialTotal());
    }

    /** The enclosing method/type element of a node, for the synthesized match's element. */
    private IJavaElement enclosingElement(ICompilationUnit source, ASTNode node) {
        ASTNode n = node;
        while (n != null && !(n instanceof MethodDeclaration)) {
            n = n.getParent();
        }
        if (n instanceof MethodDeclaration md && md.resolveBinding() != null) {
            IJavaElement el = md.resolveBinding().getJavaElement();
            if (el != null) {
                return el;
            }
        }
        return source;
    }

    /** The compilation units of the project's source folders; a test seam overrides it. */
    ICompilationUnit[] collectSourceCompilationUnits() throws CoreException {
        List<ICompilationUnit> units = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                        units.add(cu);
                    }
                }
            }
        }
        return units.toArray(new ICompilationUnit[0]);
    }

    /**
     * Find all references (reads and writes) to an element.
     */
    public SearchResult findAllReferences(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.REFERENCES, maxResults);
    }

    /**
     * Find references to an element restricted to the project's own SOURCE
     * {@code .java} files. Unlike {@link #findAllReferences}, this uses the
     * sources-only scope and drops any match whose resource is not a project
     * {@code .java} file — classpath jars, compiled output, and the linked
     * Eclipse workspace-metadata copies under {@code target/work}. A reference
     * search for a JDK method (e.g. {@code java.lang.reflect.Field.get} from
     * find_reflection_usage) therefore reports only the project's own call
     * sites, never matches harvested from the classpath or the workspace. The
     * references of a constructor include its delegations that are not the first
     * statement of a constructor body, as {@link #findReferences} reports them. The
     * places the search reports but cannot confirm are kept apart, as
     * {@link SearchResult#potentialMatches()}.
     */
    public SearchResult findAllReferencesInSources(IJavaElement element, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES);
        if (pattern == null) {
            log.warn("Cannot create pattern for element: {}", element);
            return new SearchResult(List.of(), 0);
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults, true);
        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            sourceScope,
            requestor,
            Deadline.monitor()
        );
        SearchResult result = requestor.toResult();
        log.debug("Source-scoped reference search for {} found {} (.java total={}, potential={})",
            element.getElementName(), result.matches().size(), result.totalEncountered(), result.potentialTotal());
        if (element instanceof IMethod m && m.isConstructor()) {
            return supplementConstructorDelegations(m, result, maxResults);
        }
        return result;
    }

    /**
     * Find the declarations of an element in the project's own source {@code .java} files, as
     * JDT's search reports them. For a method these include each lambda expression implementing
     * it (MatchLocator.reportMatching(LambdaExpression)), reported with the lambda's own method as
     * its element and the span from the lambda's start to its arrow. The places the search reports
     * but cannot confirm are kept apart, as {@link SearchResult#potentialMatches()}.
     *
     * @throws IllegalArgumentException when JDT's search has no pattern for {@code element}
     */
    public SearchResult findDeclarationsInSources(IJavaElement element, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(element, IJavaSearchConstants.DECLARATIONS);
        if (pattern == null) {
            throw new IllegalArgumentException("JDT's search has no declaration pattern for " + element);
        }
        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults, true);
        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            sourceScope,
            requestor,
            Deadline.monitor()
        );
        return requestor.toResult();
    }

    /**
     * Find only read accesses to a field.
     * AI-centric: helps identify unused or write-only fields.
     */
    public SearchResult findReadAccesses(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.READ_ACCESSES, maxResults);
    }

    /**
     * Find only write accesses to a field.
     * AI-centric: helps identify read-only fields.
     */
    public SearchResult findWriteAccesses(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.WRITE_ACCESSES, maxResults);
    }

    /**
     * Find implementations of an interface or overrides of a method; the places the search
     * reports but cannot confirm are kept apart, as {@link SearchResult#potentialMatches()}.
     */
    public SearchResult findImplementations(IJavaElement element, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            element,
            IJavaSearchConstants.IMPLEMENTORS
        );

        if (pattern == null) {
            log.warn("Cannot create implementors pattern for: {}", element);
            return new SearchResult(List.of(), 0);
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults, false);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            Deadline.monitor()
        );

        return requestor.toResult();
    }

    /**
     * The supertypes and subtypes of {@code type}, and the methods overriding its methods or
     * overridden by them, as the Java model holds them ({@link ModelHierarchy}): local and
     * anonymous classes under the model's numbering, in and after lambda bodies. Computed for this
     * call; nothing is kept between calls.
     *
     * @param type a type of the Java model, source or class file
     * @throws IllegalArgumentException when {@code type} is a lambda expression, which the model
     *     does not hold as a type
     */
    public ModelHierarchy hierarchyOf(IType type) throws CoreException {
        return new ModelHierarchy(type, project, engine, scope);
    }

    /**
     * The direct subtypes an {@code IMPLEMENTORS} search for {@code type} reports by a handle the
     * Java model does not hold, each named as the model holds it ({@link #withModelElement}): a
     * class declared in a lambda body, which search places under the lambda. JDT's pull up reads
     * the element of each such match as a type (PullUpRefactoringProcessor.checkIfHidingMethod
     * :1239-1259, through findImplementors :2046-2057) and fails on one that does not exist. Only
     * accurate matches count, as JDT's search filters the others. By file and position.
     */
    public List<IType> implementorsWithoutModelHandles(IType type) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(type, IJavaSearchConstants.IMPLEMENTORS);
        if (pattern == null) {
            return List.of();
        }
        List<IType> found = new ArrayList<>();
        engine.search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() }, scope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    if (match.getAccuracy() == SearchMatch.A_ACCURATE && match.getElement() instanceof IType raw
                            && !raw.exists() && withModelElement(match).getElement() instanceof IType model) {
                        found.add(model);
                    }
                }
            }, Deadline.monitor());
        found.sort(ModelHierarchy.TYPE_ORDER);
        return List.copyOf(found);
    }

    /**
     * The methods that must keep one name and signature with {@code method}, as JDT's rename
     * relates them (RippleMethodFinder2.getAllRippleMethods :188-340): {@code method} and,
     * repeated from every method found until none is new, every method it overrides
     * ({@link ModelHierarchy#allOverridden}), every method that overrides it
     * ({@link ModelHierarchy#overriding}) and every method a subtype inherits together with it
     * ({@link ModelHierarchy#joinedInSubtypes}), all as the Java model holds them, in and after
     * lambda bodies included. A method of a class file is listed but searched from no further: it
     * cannot change with the others, and its overriders may be the whole JDK. By file and
     * position, class files after sources.
     *
     * @throws IllegalArgumentException when a method found is declared in a lambda expression
     */
    public List<IMethod> rippleOf(IMethod method) throws CoreException {
        Set<IMethod> ripple = new LinkedHashSet<>(List.of(method));
        Deque<IMethod> pending = new ArrayDeque<>(ripple);
        while (!pending.isEmpty()) {
            IMethod current = pending.removeFirst();
            if (current.isBinary()) {
                continue;
            }
            ModelHierarchy hierarchy = hierarchyOf(current.getDeclaringType());
            List<IMethod> related = new ArrayList<>(hierarchy.allOverridden(current));
            related.addAll(hierarchy.overriding(current));
            related.addAll(hierarchy.joinedInSubtypes(current));
            for (IMethod found : related) {
                if (ripple.add(found)) {
                    pending.addLast(found);
                }
            }
        }
        List<IMethod> sorted = new ArrayList<>(ripple);
        sorted.sort(ModelHierarchy.METHOD_ORDER);
        return List.copyOf(sorted);
    }

    // ========== Fine-Grained Reference Search ==========
    // These use IJavaSearchConstants fine-grain reference types that only JDT can do.

    /**
     * Categories of fine-grained type references that JDT can distinguish
     * (LSP cannot). Each maps to one {@code IJavaSearchConstants.*_TYPE_REFERENCE}
     * value via the internal {@link #JDT_KIND} table.
     */
    public enum ReferenceKind {
        ANNOTATION,
        INSTANTIATION,
        CAST,
        INSTANCEOF,
        THROWS_CLAUSE,
        CATCH,
        TYPE_ARGUMENT
    }

    private static final java.util.Map<ReferenceKind, Integer> JDT_KIND;
    static {
        var m = new java.util.EnumMap<ReferenceKind, Integer>(ReferenceKind.class);
        m.put(ReferenceKind.ANNOTATION, IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE);
        m.put(ReferenceKind.INSTANTIATION, IJavaSearchConstants.CLASS_INSTANCE_CREATION_TYPE_REFERENCE);
        m.put(ReferenceKind.CAST, IJavaSearchConstants.CAST_TYPE_REFERENCE);
        m.put(ReferenceKind.INSTANCEOF, IJavaSearchConstants.INSTANCEOF_TYPE_REFERENCE);
        m.put(ReferenceKind.THROWS_CLAUSE, IJavaSearchConstants.THROWS_CLAUSE_TYPE_REFERENCE);
        m.put(ReferenceKind.CATCH, IJavaSearchConstants.CATCH_TYPE_REFERENCE);
        m.put(ReferenceKind.TYPE_ARGUMENT, IJavaSearchConstants.TYPE_ARGUMENT_TYPE_REFERENCE);
        JDT_KIND = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Find fine-grained references to a type by category. The category maps to
     * a JDT {@code IJavaSearchConstants.*_TYPE_REFERENCE} value internally.
     * JDT-unique: LSP cannot distinguish these reference shapes.
     */
    public SearchResult findReferences(IType type, ReferenceKind kind, int maxResults) throws CoreException {
        return findFineGrainReferences(type, JDT_KIND.get(kind), maxResults);
    }

    /**
     * Find all method reference expressions ({@code Foo::bar} lambdas).
     * JDT-unique: distinct from the type-reference enum above because it
     * searches against an {@link IMethod}, not an {@link IType}.
     */
    public SearchResult findMethodReferences(IMethod method, int maxResults) throws CoreException {
        return findReferences(method, IJavaSearchConstants.METHOD_REFERENCE_EXPRESSION, maxResults);
    }

    /**
     * Helper method for fine-grain type reference searches.
     * Uses string-based pattern for better match info in fine-grain searches.
     */
    private SearchResult findFineGrainReferences(IType type, int referenceType, int maxResults) throws CoreException {
        // Use $-qualified name for nested types so JDT's string-based pattern matcher
        // resolves the correct IType. Top-level types are unaffected (no $).
        String typeName = type.getFullyQualifiedName('$');

        SearchPattern pattern = SearchPattern.createPattern(
            typeName,
            IJavaSearchConstants.TYPE,
            referenceType,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE
        );

        if (pattern == null) {
            log.warn("Cannot create fine-grain pattern for type: {} with reference type: {}", typeName, referenceType);
            return new SearchResult(List.of(), 0);
        }

        // Keep only matches in .java files (not linked-folder roots, binary entries, JDK jars),
        // searching sourceScope (project sources only) so common JDK types don't pull every JDK
        // match through. The totals count every match, beyond the clipped lists, so callers
        // detect truncation exactly; places the search cannot confirm are kept apart.
        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults, true);
        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            sourceScope,
            requestor,
            Deadline.monitor()
        );

        SearchResult result = requestor.toResult();
        log.debug("Fine-grain search for {} (type={}) found {} after .java filter (total={}, potential={})",
            type.getFullyQualifiedName(), referenceType, result.matches().size(), result.totalEncountered(),
            result.potentialTotal());
        return result;
    }

    /**
     * {@code match}, its element replaced by the one the Java model holds at the match position
     * when search named it with a handle of its own: anything in a local or anonymous class or
     * in a lambda expression. Search counts the classes of a lambda body under the lambda, and
     * the classes written directly in a member without those of its lambdas, while the model,
     * which has no lambda elements, counts both under the member; such a handle names a class the
     * model does not hold or a sibling of the right one. A reference in a lambda body belongs,
     * in the model, to the nearest enclosing member. A declaration keeps its kind and name, so a
     * lambda's own declaration is left as search reports it.
     *
     * <p>A reference search places in a lambda body lies in no class body within the lambda, yet
     * it may lie in the creation of an anonymous class there, in the type it names or in its
     * arguments: the model's anonymous class starts at that type name, as the parser places it
     * (Parser.consumeEnterAnonymousClassBody), so the model's element at such a reference is the
     * anonymous class, and the reference belongs to the member enclosing the creation.
     */
    static SearchMatch withModelElement(SearchMatch match) {
        if (!(match.getElement() instanceof IJavaElement element) || !withinLocalScope(element)
            || !(element.getAncestor(IJavaElement.COMPILATION_UNIT) instanceof ICompilationUnit unit)) {
            return match;
        }
        try {
            IJavaElement model = unit.getElementAt(match.getOffset());
            if (model != null && match instanceof ReferenceMatch && isLambda(element)) {
                while (model instanceof IType type && type.isAnonymous()) {
                    model = model.getParent();
                }
                match.setElement(model);
            } else if (model != null && model.getElementType() == element.getElementType()
                    && model.getElementName().equals(element.getElementName())) {
                match.setElement(model);
            }
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read the element at offset " + match.getOffset() + " of "
                + unit.getElementName() + ": " + e.getMessage(), e);
        }
        return match;
    }

    /** Whether {@code element} or an element enclosing it is a local or anonymous class or a lambda. */
    private static boolean withinLocalScope(IJavaElement element) {
        for (IJavaElement current = element; current != null; current = current.getParent()) {
            if (current instanceof IType type && (type.isLambda() || isLocalOrAnonymous(type))) {
                return true;
            }
            if (current instanceof IMethod method && method.isLambdaMethod()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalOrAnonymous(IType type) {
        try {
            return type.isLocal() || type.isAnonymous();
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read the kind of " + type.getElementName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean isLambda(IJavaElement element) {
        return (element instanceof IMethod method && method.isLambdaMethod())
            || (element instanceof IType type && type.isLambda());
    }

    /**
     * Translate a glob (`*` = any chars, `?` = single char) to a Java regex,
     * quoting all other regex metacharacters so identifier punctuation in the
     * pattern is treated literally.
     */
    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() + 4);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    out.append(java.util.regex.Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                out.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            out.append(java.util.regex.Pattern.quote(literal.toString()));
        }
        return out.toString();
    }

    /**
     * Get the search scope.
     */
    public IJavaSearchScope getScope() {
        return scope;
    }

    /**
     * Get the project.
     */
    public IJavaProject getProject() {
        return project;
    }

    /**
     * SearchRequestor that keeps the accurate matches and the potential ones
     * ({@link SearchMatch#A_INACCURATE}: the search could not confirm them) apart, each in a
     * list clipped at {@code maxResults} and with a count of all of them (including those beyond
     * the cap). The totals enable correct truncation semantics: comparing the post-clip list size
     * to maxResults misreports truncation when the actual count exactly equals the cap. With
     * {@code javaFilesOnly}, only matches in the project's {@code .java} files are kept.
     */
    private static class CollectingSearchRequestor extends SearchRequestor {
        private final List<SearchMatch> matches = new ArrayList<>();
        private final List<SearchMatch> potentialMatches = new ArrayList<>();
        private final int maxResults;
        private final boolean javaFilesOnly;
        private int totalEncountered = 0;
        private int potentialTotal = 0;

        CollectingSearchRequestor(int maxResults, boolean javaFilesOnly) {
            this.maxResults = maxResults;
            this.javaFilesOnly = javaFilesOnly;
        }

        @Override
        public void acceptSearchMatch(SearchMatch match) {
            if (javaFilesOnly && !isInJavaFile(match)) {
                return;
            }
            if (match.getAccuracy() == SearchMatch.A_INACCURATE) {
                potentialTotal++;
                if (potentialMatches.size() < maxResults) {
                    potentialMatches.add(withModelElement(match));
                }
                return;
            }
            totalEncountered++;
            if (matches.size() < maxResults) {
                matches.add(withModelElement(match));
            }
        }

        SearchResult toResult() {
            return new SearchResult(matches, totalEncountered, potentialMatches, potentialTotal);
        }
    }
}
