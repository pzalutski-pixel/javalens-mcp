package org.javalens.core.jdt;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.FieldReferenceMatch;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeReferenceMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins behavior of the Eclipse JDT API that JavaLens relies on. Failures here
 * mean **JDT changed**, not that our wrapper broke. They isolate JDT-version
 * upgrades from refactor regressions.
 *
 * <p>Sourced from invariants embedded in {@code JdtServiceImpl},
 * {@code SearchService}, {@code FindReferencesTool}, {@code TypeKindResolver},
 * and {@code MatchResolver}.
 */
class JdtContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    // ========== findType — nested types: dotted vs $-form ==========

    @Test
    @DisplayName("findType resolves nested types by dotted name: 'com.example.Outer.Inner'")
    void findType_nestedDotted_resolves() {
        IType color = service.findType("com.example.TypeKindsFixture.Color");
        assertNotNull(color, "JDT must accept dotted nested type names via findType");
    }

    @Test
    @DisplayName("findType does NOT accept $-form for nested types — only dotted")
    void findType_nestedDollar_returnsNull() {
        // JDT contract: IJavaProject.findType accepts only dotted FQN, not $-form.
        // The $-form is exclusive to SearchPattern.createPattern(typeName, ...) strings,
        // where SearchService.findFineGrainReferences passes type.getFullyQualifiedName('$').
        // If JDT ever changes this and accepts $-form via findType, our callers don't break
        // — but the test will surface the change.
        IType color = service.findType("com.example.TypeKindsFixture$Color");
        org.junit.jupiter.api.Assertions.assertNull(color,
            "JDT contract: findType accepts only dotted form. $-form must return null.");
    }

    // ========== isAnnotation / isInterface ordering quirk ==========

    @Test
    @DisplayName("@interface reports BOTH isAnnotation()=true AND isInterface()=true")
    void atInterface_reportsBothFlags() throws Exception {
        IType marker = service.findType("com.example.Marker");
        assertNotNull(marker);
        assertTrue(marker.isAnnotation(),
            "Marker is an @interface — isAnnotation() must return true");
        assertTrue(marker.isInterface(),
            "JDT quirk: @interface ALSO reports isInterface()=true. " +
            "Any kind-dispatch must check isAnnotation() FIRST or annotations will misclassify.");
    }

    @Test
    @DisplayName("Regular interface reports isInterface()=true, isAnnotation()=false")
    void plainInterface_doesNotReportAnnotation() throws Exception {
        IType iShape = service.findType("com.example.IShape");
        assertNotNull(iShape);
        assertTrue(iShape.isInterface());
        assertFalse(iShape.isAnnotation(),
            "Plain interface must NOT report isAnnotation()=true");
    }

    // ========== SearchMatch subclass per element kind ==========

    @Test
    @DisplayName("TYPE+REFERENCES search returns TypeReferenceMatch instances")
    void typeReferences_yieldTypeReferenceMatch() throws Exception {
        List<SearchMatch> matches = runSearch(SearchPattern.createPattern(
            "com.example.Calculator",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.REFERENCES,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE));
        assertFalse(matches.isEmpty(),
            "Calculator must have at least one type reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof TypeReferenceMatch,
                "Every TYPE+REFERENCES match must be a TypeReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    @Test
    @DisplayName("METHOD+REFERENCES search returns MethodReferenceMatch instances")
    void methodReferences_yieldMethodReferenceMatch() throws Exception {
        // Calculator.add has known call sites in fixtures.
        IType calc = service.findType("com.example.Calculator");
        assertNotNull(calc);
        var addMethods = calc.getMethods();
        var add = java.util.Arrays.stream(addMethods)
            .filter(m -> "add".equals(m.getElementName()))
            .findFirst().orElseThrow();
        List<SearchMatch> matches = runSearch(
            SearchPattern.createPattern(add, IJavaSearchConstants.REFERENCES));
        assertFalse(matches.isEmpty(),
            "Calculator.add must have at least one reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof MethodReferenceMatch,
                "Every METHOD+REFERENCES match must be a MethodReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    @Test
    @DisplayName("FIELD+REFERENCES search returns FieldReferenceMatch instances")
    void fieldReferences_yieldFieldReferenceMatch() throws Exception {
        // Calculator.lastResult has known read+write references.
        IType calc = service.findType("com.example.Calculator");
        var lastResult = calc.getField("lastResult");
        assertNotNull(lastResult);
        assertTrue(lastResult.exists(), "Calculator.lastResult field must resolve");
        List<SearchMatch> matches = runSearch(
            SearchPattern.createPattern(lastResult, IJavaSearchConstants.REFERENCES));
        assertFalse(matches.isEmpty(),
            "Calculator.lastResult must have at least one reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof FieldReferenceMatch,
                "Every FIELD+REFERENCES match must be a FieldReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    // ========== R_PATTERN_MATCH vs R_REGEXP_MATCH semantics ==========

    @Test
    @DisplayName("R_PATTERN_MATCH supports * wildcard")
    void patternMatch_starWildcard_matches() throws Exception {
        List<SearchMatch> matches = runSearch(SearchPattern.createPattern(
            "Calc*",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE));
        assertFalse(matches.isEmpty(),
            "R_PATTERN_MATCH must support * — 'Calc*' must match Calculator");
    }

    @Test
    @DisplayName("R_PATTERN_MATCH does NOT support ? wildcard (informs SearchService.searchSymbols)")
    void patternMatch_questionWildcard_doesNotMatch() throws Exception {
        // This is the JDT behavior that SearchService.searchSymbols works around
        // via broaden-then-client-filter. JDT may either return a null pattern
        // outright (rejecting `?`) or produce a pattern that matches zero things.
        // Both outcomes are consistent with "JDT does not honor `?` here." If JDT
        // ever DOES support ? in R_PATTERN_MATCH, this test will fail and we can
        // simplify SearchService.
        SearchPattern pattern = SearchPattern.createPattern(
            "?alculator",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE);
        if (pattern == null) {
            // JDT rejected the pattern. Acceptable — the contract holds.
            return;
        }
        List<SearchMatch> matches = runSearchAcceptingNull(pattern);
        assertTrue(matches.isEmpty(),
            "JDT contract: R_PATTERN_MATCH treats '?' as a literal char, not a wildcard. " +
            "If this becomes non-empty, JDT added ?-glob support and " +
            "SearchService.searchSymbols can drop its broaden-then-filter shim.");
    }

    // ========== Helpers ==========

    private List<SearchMatch> runSearch(SearchPattern pattern) throws Exception {
        assertNotNull(pattern, "JDT must produce a non-null SearchPattern for valid inputs");
        return runSearchAcceptingNull(pattern);
    }

    private List<SearchMatch> runSearchAcceptingNull(SearchPattern pattern) throws Exception {
        List<SearchMatch> matches = new ArrayList<>();
        if (pattern == null) return matches;
        new SearchEngine().search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            service.getSearchService().getScope(),
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch m) {
                    matches.add(m);
                }
            },
            new NullProgressMonitor());
        return matches;
    }
}
