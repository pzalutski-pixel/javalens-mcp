package org.javalens.core.search;

import org.eclipse.jdt.core.search.SearchMatch;

import java.util.List;

/**
 * The matches a search confirmed and those it could not, each with the count of all of them.
 *
 * <p>{@code matches} is the (possibly clipped-at-maxResults) list of accurate matches
 * ({@link SearchMatch#A_ACCURATE}) and {@code totalEncountered} the count of every accurate match
 * the search reported. {@code truncated()} is the canonical "more matches exist than were
 * returned" signal; tools must use this rather than comparing the post-clip list size to
 * maxResults, which fires a false-positive when the actual count exactly equals the cap.
 *
 * <p>{@code potentialMatches} is the (clipped at the same maxResults) list of the places the search
 * reported as possible matches it could not confirm ({@link SearchMatch#A_INACCURATE}: a compile
 * problem, such as a type or name that does not resolve, kept it from resolving the place), and
 * {@code potentialTotal} the count of all of them. They are never among {@code matches} nor counted
 * in {@code totalEncountered}.
 */
public record SearchResult(List<SearchMatch> matches, int totalEncountered,
                           List<SearchMatch> potentialMatches, int potentialTotal) {

    /** A result with no potential matches. */
    public SearchResult(List<SearchMatch> matches, int totalEncountered) {
        this(matches, totalEncountered, List.of(), 0);
    }

    public boolean truncated() {
        return totalEncountered > matches.size();
    }
}
