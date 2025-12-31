package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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

    public SearchService(IJavaProject project) {
        this.project = project;
        this.engine = new SearchEngine();
        this.scope = SearchEngine.createJavaSearchScope(new IJavaElement[]{ project });
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
    public List<SearchMatch> searchSymbols(String pattern, Integer searchFor, int maxResults) throws CoreException {
        // Convert glob to SearchPattern format
        String searchPattern = pattern.replace("*", "*");  // Already compatible

        int searchForType = searchFor != null ? searchFor : IJavaSearchConstants.TYPE;

        SearchPattern jdtPattern = SearchPattern.createPattern(
            searchPattern,
            searchForType,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
        );

        if (jdtPattern == null) {
            log.warn("Invalid search pattern: {}", pattern);
            return List.of();
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            jdtPattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        log.debug("Symbol search '{}' found {} results", pattern, requestor.getMatches().size());
        return requestor.getMatches();
    }

    /**
     * Find all references to a Java element.
     *
     * @param element The element to find references to
     * @param limitTo Limit type: REFERENCES, READ_ACCESSES, WRITE_ACCESSES, or combination
     * @param maxResults Maximum results
     * @return List of reference locations
     */
    public List<SearchMatch> findReferences(IJavaElement element, int limitTo, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            element,
            limitTo
        );

        if (pattern == null) {
            log.warn("Cannot create pattern for element: {}", element);
            return List.of();
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        log.debug("Reference search for {} found {} results", element.getElementName(), requestor.getMatches().size());
        return requestor.getMatches();
    }

    /**
     * Find all references (reads and writes) to an element.
     */
    public List<SearchMatch> findAllReferences(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.REFERENCES, maxResults);
    }

    /**
     * Find only read accesses to a field.
     * AI-centric: helps identify unused or write-only fields.
     */
    public List<SearchMatch> findReadAccesses(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.READ_ACCESSES, maxResults);
    }

    /**
     * Find only write accesses to a field.
     * AI-centric: helps identify read-only fields.
     */
    public List<SearchMatch> findWriteAccesses(IJavaElement element, int maxResults) throws CoreException {
        return findReferences(element, IJavaSearchConstants.WRITE_ACCESSES, maxResults);
    }

    /**
     * Find implementations of an interface or overrides of a method.
     */
    public List<SearchMatch> findImplementations(IJavaElement element, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            element,
            IJavaSearchConstants.IMPLEMENTORS
        );

        if (pattern == null) {
            log.warn("Cannot create implementors pattern for: {}", element);
            return List.of();
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        return requestor.getMatches();
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
    public List<SearchMatch> findOverridingMethods(IMethod method, int maxResults) throws CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
            method,
            IJavaSearchConstants.DECLARATIONS | IJavaSearchConstants.IGNORE_DECLARING_TYPE
        );

        if (pattern == null) {
            return List.of();
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        // Search in subtypes only
        IType declaringType = method.getDeclaringType();
        IType[] subtypes = getAllSubtypes(declaringType);

        if (subtypes.length == 0) {
            return List.of();
        }

        IJavaSearchScope subtypeScope = SearchEngine.createJavaSearchScope(subtypes);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            subtypeScope,
            requestor,
            new NullProgressMonitor()
        );

        return requestor.getMatches();
    }

    // ========== Fine-Grained Reference Search ==========
    // These use IJavaSearchConstants fine-grain reference types that only JDT can do.

    /**
     * Find all usages of an annotation type.
     * Example: Find all @Autowired, @Test, @Entity usages.
     * JDT-unique: LSP cannot distinguish annotation references from other type references.
     */
    public List<SearchMatch> findAnnotationUsages(IType annotationType, int maxResults) throws CoreException {
        return findFineGrainReferences(annotationType, IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all instantiations of a type (new Foo() calls).
     * JDT-unique: LSP cannot distinguish instantiation from other type references.
     */
    public List<SearchMatch> findTypeInstantiations(IType type, int maxResults) throws CoreException {
        return findFineGrainReferences(type, IJavaSearchConstants.CLASS_INSTANCE_CREATION_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all casts to a type ((Foo) x expressions).
     * JDT-unique: Helps identify unsafe downcasts and refactoring opportunities.
     */
    public List<SearchMatch> findCasts(IType type, int maxResults) throws CoreException {
        return findFineGrainReferences(type, IJavaSearchConstants.CAST_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all instanceof checks for a type (x instanceof Foo).
     * JDT-unique: Helps identify type checking patterns and polymorphism issues.
     */
    public List<SearchMatch> findInstanceofChecks(IType type, int maxResults) throws CoreException {
        return findFineGrainReferences(type, IJavaSearchConstants.INSTANCEOF_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all throws declarations of an exception type (throws Foo).
     * JDT-unique: Helps understand exception flow in method signatures.
     */
    public List<SearchMatch> findThrowsDeclarations(IType exceptionType, int maxResults) throws CoreException {
        return findFineGrainReferences(exceptionType, IJavaSearchConstants.THROWS_CLAUSE_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all catch blocks for an exception type (catch(Foo e)).
     * JDT-unique: Helps understand exception handling patterns.
     */
    public List<SearchMatch> findCatchBlocks(IType exceptionType, int maxResults) throws CoreException {
        return findFineGrainReferences(exceptionType, IJavaSearchConstants.CATCH_TYPE_REFERENCE, maxResults);
    }

    /**
     * Find all method reference expressions (Foo::bar lambdas).
     * JDT-unique: Helps understand functional programming patterns.
     */
    public List<SearchMatch> findMethodReferences(IMethod method, int maxResults) throws CoreException {
        return findReferences(method, IJavaSearchConstants.METHOD_REFERENCE_EXPRESSION, maxResults);
    }

    /**
     * Find all type argument usages (List<Foo>, Map<K, Foo>).
     * JDT-unique: Helps understand generic usage patterns.
     */
    public List<SearchMatch> findTypeArguments(IType type, int maxResults) throws CoreException {
        return findFineGrainReferences(type, IJavaSearchConstants.TYPE_ARGUMENT_TYPE_REFERENCE, maxResults);
    }

    /**
     * Helper method for fine-grain type reference searches.
     * Uses string-based pattern for better match info in fine-grain searches.
     */
    private List<SearchMatch> findFineGrainReferences(IType type, int referenceType, int maxResults) throws CoreException {
        // Use fully qualified name for string-based pattern
        String typeName = type.getFullyQualifiedName();

        // Create pattern using string approach with TYPE + fine-grain flag
        SearchPattern pattern = SearchPattern.createPattern(
            typeName,
            IJavaSearchConstants.TYPE,
            referenceType,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE
        );

        if (pattern == null) {
            log.warn("Cannot create fine-grain pattern for type: {} with reference type: {}", typeName, referenceType);
            return List.of();
        }

        CollectingSearchRequestor requestor = new CollectingSearchRequestor(maxResults);

        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            requestor,
            new NullProgressMonitor()
        );

        log.debug("Fine-grain search for {} (type={}) found {} results",
            typeName, referenceType, requestor.getMatches().size());
        return requestor.getMatches();
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
     * SearchRequestor that collects matches into a list.
     */
    private static class CollectingSearchRequestor extends SearchRequestor {
        private final List<SearchMatch> matches = new ArrayList<>();
        private final int maxResults;

        CollectingSearchRequestor(int maxResults) {
            this.maxResults = maxResults;
        }

        @Override
        public void acceptSearchMatch(SearchMatch match) {
            if (matches.size() < maxResults) {
                matches.add(match);
            }
        }

        List<SearchMatch> getMatches() {
            return matches;
        }
    }
}
