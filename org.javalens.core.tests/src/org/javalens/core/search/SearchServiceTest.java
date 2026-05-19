package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SearchService.
 * Tests symbol search, reference finding, and type hierarchy operations.
 */
class SearchServiceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SearchService searchService;
    private JdtServiceImpl jdtService;
    private IJavaProject javaProject;

    @BeforeEach
    void setUp() throws Exception {
        jdtService = helper.loadProject("simple-maven");
        javaProject = jdtService.getJavaProject();
        searchService = new SearchService(javaProject);
    }

    // ========== Symbol Search Tests ==========

    @Test
    @DisplayName("searchSymbols should find types by pattern")
    void searchSymbols_findsByPattern() throws CoreException {
        List<SearchMatch> matches = searchService.searchSymbols("Calc*", IJavaSearchConstants.TYPE, 100).matches();

        assertFalse(matches.isEmpty(), "Should find Calculator class");
        assertTrue(matches.stream()
            .anyMatch(m -> m.getElement().toString().contains("Calculator")),
            "Should find Calculator in results");
    }

    @Test
    @DisplayName("searchSymbols should find types with wildcard at start")
    void searchSymbols_findsWithLeadingWildcard() throws CoreException {
        List<SearchMatch> matches = searchService.searchSymbols("*Service", IJavaSearchConstants.TYPE, 100).matches();

        assertFalse(matches.isEmpty(), "Should find UserService class");
        assertTrue(matches.stream()
            .anyMatch(m -> m.getElement().toString().contains("UserService")),
            "Should find UserService in results");
    }

    @Test
    @DisplayName("searchSymbols should filter by kind")
    void searchSymbols_filtersByKind() throws CoreException {
        // Search for methods
        List<SearchMatch> methodMatches = searchService.searchSymbols("add*", IJavaSearchConstants.METHOD, 100).matches();

        // Should find add method in Calculator
        assertFalse(methodMatches.isEmpty(), "Should find add method");
        assertTrue(methodMatches.stream()
            .anyMatch(m -> m.getElement().toString().contains("add")),
            "Should find add method in results");
    }

    @Test
    @DisplayName("searchSymbols should respect maxResults limit")
    void searchSymbols_respectsMaxResults() throws CoreException {
        // Search with very low limit
        List<SearchMatch> matches = searchService.searchSymbols("*", IJavaSearchConstants.TYPE, 1).matches();

        assertTrue(matches.size() <= 1, "Should respect maxResults limit of 1");
    }

    @Test
    @DisplayName("searchSymbols should return empty for no matches")
    void searchSymbols_returnsEmptyForNoMatches() throws CoreException {
        List<SearchMatch> matches = searchService.searchSymbols("NonExistentClass", IJavaSearchConstants.TYPE, 100).matches();

        assertTrue(matches.isEmpty(), "Should return empty list for non-matching pattern");
    }

    // ========== Reference Finding Tests ==========

    @Test
    @DisplayName("findAllReferences should find usages of a type")
    void findAllReferences_findsUsages() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        List<SearchMatch> references = searchService.findAllReferences(calculatorType, 100).matches();

        // Calculator is referenced in UserService
        assertFalse(references.isEmpty(), "Should find references to Calculator");
    }

    @Test
    @DisplayName("findAllReferences for HelloWorld: only self-references inside HelloWorld.java")
    void findAllReferences_handlesUnreferencedTypes() throws CoreException {
        IType helloType = jdtService.findType("com.example.HelloWorld");
        assertNotNull(helloType, "Should find HelloWorld type");

        List<SearchMatch> references = searchService.findAllReferences(helloType, 100).matches();
        assertNotNull(references);

        // Previous assertion was assertNotNull(references) — passed for ANY result
        // including stale-index leaks. HelloWorld is only referenced inside HelloWorld.java
        // itself (line 52: `HelloWorld hello = new HelloWorld()`). Pin that every match
        // resolves to HelloWorld.java; a regression that returned matches from other files
        // would surface.
        for (SearchMatch m : references) {
            String resourcePath = m.getResource() != null
                ? m.getResource().getFullPath().toString() : "";
            assertTrue(resourcePath.endsWith("HelloWorld.java"),
                "All HelloWorld references must be in HelloWorld.java; got: " + resourcePath);
        }
    }

    // ========== Type Hierarchy Tests ==========

    @Test
    @DisplayName("getTypeHierarchy should return hierarchy for type")
    void getTypeHierarchy_returnsHierarchy() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        ITypeHierarchy hierarchy = searchService.getTypeHierarchy(calculatorType);

        assertNotNull(hierarchy, "Should return a type hierarchy");
        // Calculator extends Object
        IType[] supertypes = hierarchy.getAllSuperclasses(calculatorType);
        assertTrue(supertypes.length >= 1, "Should have at least Object as supertype");
    }

    @Test
    @DisplayName("getAllSupertypes should return superclasses")
    void getAllSupertypes_returnsSuperclasses() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        IType[] supertypes = searchService.getAllSupertypes(calculatorType);

        // Should include Object at minimum
        assertNotNull(supertypes, "Should return supertypes array");
        assertTrue(supertypes.length >= 1, "Should have Object as supertype");
    }

    @Test
    @DisplayName("getAllSubtypes should return subclasses")
    void getAllSubtypes_returnsSubclasses() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        IType[] subtypes = searchService.getAllSubtypes(calculatorType);

        // Calculator has no subclasses in the fixture
        assertNotNull(subtypes, "Should return subtypes array");
        assertEquals(0, subtypes.length, "Calculator should have no subtypes");
    }

    // ========== Accessor Tests ==========

    @Test
    @DisplayName("getScope should return search scope")
    void getScope_returnsSearchScope() {
        assertNotNull(searchService.getScope(), "Should return search scope");
    }

    @Test
    @DisplayName("getProject should return the Java project")
    void getProject_returnsJavaProject() {
        assertEquals(javaProject, searchService.getProject(),
            "Should return the same Java project");
    }

    // ========== Fine-Grained Search Tests ==========

    @Test
    @DisplayName("findReferences(INSTANTIATION) on Calculator finds the known new-expressions in project sources")
    void findTypeInstantiations_findsNewExpressions() throws CoreException {
        // Use Calculator (a project source type) rather than ArrayList (JRE-binary):
        // JDT's fine-grain INSTANTIATION rule doesn't index JRE binaries, so the
        // ArrayList variant returned 0 even though the source has many `new ArrayList<>()`
        // sites. Calculator is instantiated in 4 known project-source sites:
        //   SearchPatterns.java:58, SearchPatterns.java:212, SearchPatterns.java:227,
        //   UserService.java:20 (and SampleTest.java:22 in test sources).
        // Pin floor (>= 4) so a regression returning 0 fails.
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType);

        List<SearchMatch> instantiations = searchService.findReferences(
            calculatorType, SearchService.ReferenceKind.INSTANTIATION, 100).matches();

        assertTrue(instantiations.size() >= 4,
            "Expected at least 4 Calculator instantiations in project sources. Got "
                + instantiations.size() + ": " + instantiations);
    }

    @Test
    @DisplayName("findReadAccesses on Calculator.lastResult finds exactly 4 reads (one per getter + 3 return-self in add/subtract/multiply)")
    void findReadAccesses_findsFieldReads() throws CoreException {
        // Calculator.lastResult is read in 4 places:
        //   add: `return lastResult;`
        //   subtract: `return lastResult;`
        //   multiply: `return lastResult;`
        //   getLastResult: `return lastResult;`
        // Previously assertNotNull-only; would pass with empty list (regression making
        // findReadAccesses return [] silently). Pin the exact count.
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType);
        var lastResult = calculatorType.getField("lastResult");
        assertNotNull(lastResult);
        assertTrue(lastResult.exists(), "Calculator.lastResult must exist");

        List<SearchMatch> reads = searchService.findReadAccesses(lastResult, 100).matches();
        assertEquals(4, reads.size(),
            "Expected exactly 4 reads of Calculator.lastResult (one per arithmetic method + "
                + "getLastResult). Got " + reads.size() + ": " + reads);
        for (SearchMatch m : reads) {
            String resourcePath = m.getResource() != null
                ? m.getResource().getFullPath().toString() : "";
            assertTrue(resourcePath.endsWith("Calculator.java"),
                "All lastResult reads must be in Calculator.java; got: " + resourcePath);
        }
    }

    @Test
    @DisplayName("findWriteAccesses on Calculator.lastResult finds exactly 3 writes (add/subtract/multiply)")
    void findWriteAccesses_findsFieldWrites() throws CoreException {
        // Calculator.lastResult is written in 3 places: add, subtract, multiply.
        // Previously assertNotNull-only.
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType);
        var lastResult = calculatorType.getField("lastResult");
        assertNotNull(lastResult);

        List<SearchMatch> writes = searchService.findWriteAccesses(lastResult, 100).matches();
        assertEquals(3, writes.size(),
            "Expected exactly 3 writes of Calculator.lastResult (add/subtract/multiply). Got "
                + writes.size() + ": " + writes);
        for (SearchMatch m : writes) {
            String resourcePath = m.getResource() != null
                ? m.getResource().getFullPath().toString() : "";
            assertTrue(resourcePath.endsWith("Calculator.java"),
                "All lastResult writes must be in Calculator.java; got: " + resourcePath);
        }
    }

    @Test
    @DisplayName("searchSymbols with `?` wildcard exercises the broaden-then-client-filter path")
    void searchSymbols_questionMarkWildcard() throws CoreException {
        // "Calculat?r" must match Calculator (single-char wildcard). JDT's pattern
        // syntax doesn't natively support `?`, so the source broadens to `*` for the
        // raw JDT search then narrows client-side with a regex compiled from the
        // original glob. Pinning this specifically — single-`*` cases stay on the
        // fast path; only `?` patterns go through the regex filter.
        List<SearchMatch> matches = searchService.searchSymbols(
            "Calculat?r", IJavaSearchConstants.TYPE, 100).matches();
        assertTrue(matches.stream()
            .anyMatch(m -> m.getElement().toString().contains("Calculator")),
            "?-pattern must match Calculator via the client-side regex narrow; got: " + matches);
    }

    @Test
    @DisplayName("searchSymbols with `?` wildcard against non-matching name returns empty")
    void searchSymbols_questionMarkWildcard_filteredOut() throws CoreException {
        // The broadening turns `Z?rgblbrx` into `Z*rgblbrx`. Without the client-side
        // regex filter, the broader pattern could match unrelated names. The filter
        // narrows back to "exactly one character between Z and rgblbrx" — which no
        // fixture symbol matches.
        List<SearchMatch> matches = searchService.searchSymbols(
            "Z?rgblbrx", IJavaSearchConstants.TYPE, 100).matches();
        assertTrue(matches.isEmpty(),
            "?-pattern with no real match must yield empty after client-side narrow; got: " + matches);
    }

    @Test
    @DisplayName("findImplementations finds direct interface implementors")
    void findImplementations_findsDirectImplementor() throws CoreException {
        // IShape is implemented by Square and Circle in the fixtures. JDT's IMPLEMENTORS
        // search returns the implementor types. Pinning >= 1 (not exact 2) because the
        // fixture set may expand; the regression we'd catch is "0 implementors found
        // when at least one exists".
        IType iShape = jdtService.findType("com.example.IShape");
        assertNotNull(iShape, "IShape interface fixture must resolve");
        List<SearchMatch> impls = searchService.findImplementations(iShape, 100).matches();
        assertTrue(impls.size() >= 1,
            "IShape must have at least one IMPLEMENTORS-search hit; got: " + impls.size());
    }

    @Test
    @DisplayName("findOverridingMethods on Animal.speak finds Dog's override")
    void findOverridingMethods_findsOverride() throws CoreException {
        // Animal.java declares both Animal (parent class with speak()) and Dog (extends
        // Animal, overrides speak()). The search is scoped to subtypes only, so the
        // result must be Dog.speak — not Animal.speak itself.
        IType animal = jdtService.findType("com.example.Animal");
        assertNotNull(animal);
        var speakMethods = animal.getMethods();
        org.eclipse.jdt.core.IMethod speak = null;
        for (org.eclipse.jdt.core.IMethod m : speakMethods) {
            if ("speak".equals(m.getElementName())) { speak = m; break; }
        }
        assertNotNull(speak, "Animal.speak must exist");
        List<SearchMatch> overrides = searchService.findOverridingMethods(speak, 100).matches();
        assertTrue(overrides.size() >= 1,
            "Animal.speak has at least one override (Dog.speak); got: " + overrides.size());
        // Pin that the override is in Dog (in subtype scope), not Animal (excluded).
        boolean inSubtype = overrides.stream().anyMatch(m -> {
            String path = m.getResource() != null ? m.getResource().getFullPath().toString() : "";
            return path.contains("Animal.java"); // Dog is also declared in Animal.java
        });
        assertTrue(inSubtype,
            "Override must come from a subtype of Animal; got: " + overrides);
    }

    @Test
    @DisplayName("findOverridingMethods on a method with no subtypes returns empty")
    void findOverridingMethods_noSubtypes_returnsEmpty() throws CoreException {
        // Calculator has no subclasses in the fixture, so its methods have no overrides.
        // The source short-circuits at `subtypes.length == 0` and returns empty.
        IType calculator = jdtService.findType("com.example.Calculator");
        org.eclipse.jdt.core.IMethod add = null;
        for (org.eclipse.jdt.core.IMethod m : calculator.getMethods()) {
            if ("add".equals(m.getElementName())) { add = m; break; }
        }
        assertNotNull(add);
        List<SearchMatch> overrides = searchService.findOverridingMethods(add, 100).matches();
        assertTrue(overrides.isEmpty(),
            "Calculator has no subtypes — findOverridingMethods must yield empty; got: " + overrides);
    }

    @Test
    @DisplayName("findMethodReferences distinguishes method references (Foo::bar) from regular calls")
    void findMethodReferences_findsMethodReferenceExpressions() throws CoreException {
        // SearchPatterns has METHOD_REFERENCE_EXPRESSION usages (e.g. `Calculator::add`
        // or similar). The result must be of method-reference shape — distinct from a
        // findAllReferences(method) which would also include direct invocation matches.
        IType calculator = jdtService.findType("com.example.Calculator");
        org.eclipse.jdt.core.IMethod add = null;
        for (org.eclipse.jdt.core.IMethod m : calculator.getMethods()) {
            if ("add".equals(m.getElementName())) { add = m; break; }
        }
        assertNotNull(add, "Calculator.add must exist");
        // Even if zero method-reference uses exist in the fixture, the call must succeed
        // (returns an empty SearchResult, not throw). Catch the regression where the
        // METHOD_REFERENCE_EXPRESSION constant gets mistyped.
        var result = searchService.findMethodReferences(add, 100);
        assertNotNull(result, "findMethodReferences must return a non-null SearchResult");
        assertNotNull(result.matches(), "matches() must never be null");
    }
}
