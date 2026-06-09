package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
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
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Search for symbols matching a pattern.
     *
     * @param pattern Glob pattern (e.g., "Order*", "*Service")
     * @param searchFor Type of element: TYPE, METHOD, FIELD, or null for all
     * @param maxResults Maximum results to return
     * @return List of matching elements
     */
    public SearchResult searchSymbols(String pattern, Integer searchFor, int maxResults) throws CoreException {
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

        // If we broadened with `?`->`*`, collect more raw matches than the cap so the
        // client-side filter can find enough still-matching entries. Otherwise the cap
        // applies directly.
        int collectCap = hasQuestionMark ? Math.max(maxResults * 4, 200) : maxResults;
        CollectingSearchRequestor requestor = new CollectingSearchRequestor(collectCap);

        engine.search(
            jdtPattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        List<SearchMatch> raw = requestor.getMatches();
        if (clientFilter == null) {
            log.debug("Symbol search '{}' found {} results", pattern, raw.size());
            // When `?` was not in the pattern, requestor's totalEncountered reflects
            // the raw match count and matches.size() is clipped at maxResults — that's
            // the correct truncation signal.
            return requestor.toResult();
        }

        // Narrow with the proper glob-as-regex against the matched element's simple name.
        // Track the post-filter total encountered (including any beyond maxResults) so
        // truncation is reported correctly: the raw search may have returned more
        // entries than the broadened cap, AND the client-side ?-filter may drop some.
        List<SearchMatch> narrowed = new ArrayList<>();
        int narrowedTotal = 0;
        for (SearchMatch m : raw) {
            String name = simpleNameOf(m);
            if (name != null && clientFilter.matcher(name).matches()) {
                narrowedTotal++;
                if (narrowed.size() < maxResults) {
                    narrowed.add(m);
                }
            }
        }
        log.debug("Symbol search '{}' found {} raw, {} after client-side ?-filter",
            pattern, raw.size(), narrowedTotal);
        return new SearchResult(narrowed, narrowedTotal);
    }

    private static String simpleNameOf(SearchMatch m) {
        Object element = m.getElement();
        if (element instanceof org.eclipse.jdt.core.IJavaElement je) {
            return je.getElementName();
        }
        return null;
    }

    /**
     * Find all references to a Java element.
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

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        log.debug("Reference search for {} found {} results (total encountered={})",
            element.getElementName(), requestor.getMatches().size(), requestor.getTotalEncountered());

        SearchResult result = requestor.toResult();

        // JDT's indexed reference search misses constructor delegations
        // (super(...)/this(...)) that are NOT the first statement of a constructor —
        // the shape introduced by JEP 513 flexible constructor bodies (Java 25).
        // Supplement the result with an AST scan so those call sites are reported by
        // every reference-based tool (find_references, change_method_signature, rename,
        // call hierarchy) the same as a first-statement delegation.
        if (element instanceof IMethod m && isConstructor(m)
                && (limitTo == IJavaSearchConstants.REFERENCES
                    || limitTo == IJavaSearchConstants.ALL_OCCURRENCES)) {
            return supplementConstructorDelegations(m, result, maxResults);
        }
        return result;
    }

    private static boolean isConstructor(IMethod method) {
        try {
            return method.isConstructor();
        } catch (CoreException e) {
            return false;
        }
    }

    /**
     * Add explicit {@code super(...)} / {@code this(...)} delegations that bind to
     * {@code constructor} but were not returned by the indexed search (JDT misses
     * the ones that are not the first statement of a flexible constructor body).
     * Delegations already present in {@code base} are not duplicated.
     */
    private SearchResult supplementConstructorDelegations(IMethod constructor, SearchResult base, int maxResults) {
        try {
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
                            try {
                                added.add(new MethodReferenceMatch(
                                    enclosingElement(source, node),
                                    SearchMatch.A_ACCURATE, start, length,
                                    false, participant, source.getResource()));
                            } catch (Exception e) {
                                log.debug("Could not synthesize delegation match: {}", e.getMessage());
                            }
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
            }, new NullProgressMonitor());

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
            return new SearchResult(merged, base.totalEncountered() + added.size());
        } catch (Exception e) {
            log.warn("Could not supplement constructor delegations for {}: {}",
                constructor.getElementName(), e.getMessage());
            return base;
        }
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

    private ICompilationUnit[] collectSourceCompilationUnits() throws CoreException {
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
     * Find implementations of an interface or overrides of a method.
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

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        return requestor.toResult();
    }

    /**
     * Get type hierarchy for a type.
     * Fast because it uses the index.
     */
    public ITypeHierarchy getTypeHierarchy(IType type) throws CoreException {
        return type.newTypeHierarchy(new NullProgressMonitor());
    }

    /**
     * Find all subtypes (implementations/extensions) of a type.
     */
    public IType[] getAllSubtypes(IType type) throws CoreException {
        ITypeHierarchy hierarchy = getTypeHierarchy(type);
        return hierarchy.getAllSubtypes(type);
    }

    /**
     * Find all supertypes of a type.
     */
    public IType[] getAllSupertypes(IType type) throws CoreException {
        ITypeHierarchy hierarchy = getTypeHierarchy(type);
        return hierarchy.getAllSuperclasses(type);
    }

    /**
     * Search for methods that override a given method.
     */
    public SearchResult findOverridingMethods(IMethod method, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            method,
            IJavaSearchConstants.DECLARATIONS | IJavaSearchConstants.IGNORE_DECLARING_TYPE
        );

        if (pattern == null) {
            return new SearchResult(List.of(), 0);
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        // Search in subtypes only
        IType declaringType = method.getDeclaringType();
        IType[] subtypes = getAllSubtypes(declaringType);

        if (subtypes.length == 0) {
            return new SearchResult(List.of(), 0);
        }

        IJavaSearchScope subtypeScope = SearchEngine.createJavaSearchScope(subtypes);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            subtypeScope,
            requestor,
            new NullProgressMonitor()
        );

        return requestor.toResult();
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

        // Inline filtering requestor: drop matches whose resource isn't a .java IFile
        // (linked-folder roots, binary entries, JDK jars). Use sourceScope (project
        // sources only) so common JDK types don't pull every JDK match through.
        //
        // Track the total .java matches separately from the (clipped-at-maxResults)
        // visible list so callers can detect truncation accurately. Comparing the
        // post-clip list size to maxResults misreports the false-positive case where
        // actual matches == maxResults exactly.
        List<SearchMatch> filtered = new ArrayList<>();
        int[] totalEncountered = { 0 };
        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            sourceScope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    if (match.getResource() instanceof org.eclipse.core.resources.IFile f
                        && "java".equalsIgnoreCase(f.getFileExtension())) {
                        totalEncountered[0]++;
                        if (filtered.size() < maxResults) {
                            filtered.add(match);
                        }
                    }
                }
            },
            new NullProgressMonitor()
        );

        log.debug("Fine-grain search for {} (type={}) found {} after .java filter (total={})",
            type.getFullyQualifiedName(), referenceType, filtered.size(), totalEncountered[0]);
        return new SearchResult(filtered, totalEncountered[0]);
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
     * SearchRequestor that collects matches into a list AND counts the total number of
     * encountered matches (including those beyond the cap). The total enables correct
     * truncation semantics: comparing the post-clip list size to maxResults misreports
     * truncation when the actual count exactly equals the cap.
     */
    private static class CollectingSearchRequestor extends SearchRequestor {
        private final List<SearchMatch> matches = new ArrayList<>();
        private final int maxResults;
        private int totalEncountered = 0;

        CollectingSearchRequestor(int maxResults) {
            this.maxResults = maxResults;
        }

        @Override
        public void acceptSearchMatch(SearchMatch match) {
            totalEncountered++;
            if (matches.size() < maxResults) {
                matches.add(match);
            }
        }

        List<SearchMatch> getMatches() {
            return matches;
        }

        int getTotalEncountered() {
            return totalEncountered;
        }

        SearchResult toResult() {
            return new SearchResult(matches, totalEncountered);
        }
    }
}
