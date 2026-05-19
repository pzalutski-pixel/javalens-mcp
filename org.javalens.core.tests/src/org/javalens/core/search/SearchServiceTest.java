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
}
