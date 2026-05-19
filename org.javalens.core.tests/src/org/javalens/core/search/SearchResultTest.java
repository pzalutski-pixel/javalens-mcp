package org.javalens.core.search;

import org.eclipse.jdt.core.search.SearchMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link SearchResult}'s {@code truncated()} predicate.
 * The record is small but load-bearing: every JavaLens tool that emits a
 * {@code maxResults} envelope reads {@code truncated()} for the response
 * envelope. A regression here would silently break pagination semantics
 * across 60+ tools.
 */
class SearchResultTest {

    @Test
    @DisplayName("empty matches + total=0 → not truncated")
    void empty_notTruncated() {
        SearchResult result = new SearchResult(List.of(), 0);
        assertFalse(result.truncated(),
            "0 of 0 matches encountered is NOT truncation");
    }

    @Test
    @DisplayName("total equals matches.size() → not truncated (all encountered were returned)")
    void totalEqualsSize_notTruncated() {
        // truncated() reads matches.size() only; the elements themselves are irrelevant.
        // List.of() rejects nulls, so use Arrays.asList which accepts them.
        List<SearchMatch> three = Arrays.asList(null, null, null);
        SearchResult result = new SearchResult(three, 3);
        assertFalse(result.truncated(),
            "3 returned of 3 encountered is NOT truncation; this is the exact-cap "
                + "case that the old `list.size() == maxResults` heuristic got wrong");
    }

    @Test
    @DisplayName("total exceeds matches.size() → truncated (more existed than were returned)")
    void totalExceedsSize_truncated() {
        List<SearchMatch> one = Collections.singletonList(null);
        SearchResult result = new SearchResult(one, 5);
        assertTrue(result.truncated(),
            "1 returned of 5 encountered must be truncated=true");
    }

    @Test
    @DisplayName("Record accessors return the constructor arguments verbatim")
    void recordAccessors_returnConstructorArguments() {
        List<SearchMatch> matches = List.of();
        SearchResult result = new SearchResult(matches, 7);
        assertSame(matches, result.matches(),
            "matches() must return the same List instance (records use field aliasing)");
        assertTrue(result.totalEncountered() == 7,
            "totalEncountered() must return the constructor argument; got: " + result.totalEncountered());
    }
}
