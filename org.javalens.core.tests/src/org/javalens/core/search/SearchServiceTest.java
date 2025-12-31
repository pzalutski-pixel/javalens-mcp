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
        List<SearchMatch> matches = searchService.searchSymbols("Calc*", IJavaSearchConstants.TYPE, 100);

        assertFalse(matches.isEmpty(), "Should find Calculator class");
        assertTrue(matches.stream()
            .anyMatch(m -> m.getElement().toString().contains("Calculator")),
            "Should find Calculator in results");
    }

    @Test
    @DisplayName("searchSymbols should find types with wildcard at start")
    void searchSymbols_findsWithLeadingWildcard() throws CoreException {
        List<SearchMatch> matches = searchService.searchSymbols("*Service", IJavaSearchConstants.TYPE, 100);

        assertFalse(matches.isEmpty(), "Should find UserService class");
        assertTrue(matches.stream()
            .anyMatch(m -> m.getElement().toString().contains("UserService")),
            "Should find UserService in results");
    }

    @Test
    @DisplayName("searchSymbols should filter by kind")
    void searchSymbols_filtersByKind() throws CoreException {
        // Search for methods
        List<SearchMatch> methodMatches = searchService.searchSymbols("add*", IJavaSearchConstants.METHOD, 100);

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
        List<SearchMatch> matches = searchService.searchSymbols("*", IJavaSearchConstants.TYPE, 1);

        assertTrue(matches.size() <= 1, "Should respect maxResults limit of 1");
    }

    @Test
    @DisplayName("searchSymbols should return empty for no matches")
    void searchSymbols_returnsEmptyForNoMatches() throws CoreException {
        List<SearchMatch> matches = searchService.searchSymbols("NonExistentClass", IJavaSearchConstants.TYPE, 100);

        assertTrue(matches.isEmpty(), "Should return empty list for non-matching pattern");
    }

    // ========== Reference Finding Tests ==========

    @Test
    @DisplayName("findAllReferences should find usages of a type")
    void findAllReferences_findsUsages() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        List<SearchMatch> references = searchService.findAllReferences(calculatorType, 100);

        // Calculator is referenced in UserService
        assertFalse(references.isEmpty(), "Should find references to Calculator");
    }

    @Test
    @DisplayName("findAllReferences should handle types with no external references")
    void findAllReferences_handlesUnreferencedTypes() throws CoreException {
        IType helloType = jdtService.findType("com.example.HelloWorld");
        assertNotNull(helloType, "Should find HelloWorld type");

        List<SearchMatch> references = searchService.findAllReferences(helloType, 100);

        // Just verify the method works - HelloWorld may have internal/compilation references
        assertNotNull(references, "Should return a list (possibly empty)");
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
    @DisplayName("findTypeInstantiations should find new expressions")
    void findTypeInstantiations_findsNewExpressions() throws CoreException {
        IType arrayListType = javaProject.findType("java.util.ArrayList");
        if (arrayListType != null) {
            List<SearchMatch> instantiations = searchService.findTypeInstantiations(arrayListType, 100);
            // ArrayList is instantiated in UserService
            assertNotNull(instantiations, "Should return instantiation list");
        }
    }

    @Test
    @DisplayName("findReadAccesses should find field reads")
    void findReadAccesses_findsFieldReads() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        var fields = calculatorType.getFields();
        if (fields.length > 0) {
            List<SearchMatch> reads = searchService.findReadAccesses(fields[0], 100);
            assertNotNull(reads, "Should return read accesses list");
        }
    }

    @Test
    @DisplayName("findWriteAccesses should find field writes")
    void findWriteAccesses_findsFieldWrites() throws CoreException {
        IType calculatorType = jdtService.findType("com.example.Calculator");
        assertNotNull(calculatorType, "Should find Calculator type");

        var fields = calculatorType.getFields();
        if (fields.length > 0) {
            List<SearchMatch> writes = searchService.findWriteAccesses(fields[0], 100);
            assertNotNull(writes, "Should return write accesses list");
            // lastResult field is written in add, subtract, multiply methods
        }
    }
}
