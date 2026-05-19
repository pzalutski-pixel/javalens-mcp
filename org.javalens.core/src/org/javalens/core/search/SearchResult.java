package org.javalens.core.search;

import org.eclipse.jdt.core.search.SearchMatch;

import java.util.List;

/**
 * Bundle of search matches plus the pre-clip count.
 *
 * <p>{@code matches} is the (possibly clipped-at-maxResults) list returned to the
 * caller. {@code totalEncountered} is the total number of {@code acceptSearchMatch}
 * calls JDT made — i.e. the true pre-clip match count. {@code truncated()} is the
 * canonical "more matches exist than were returned" signal; tools must use this
 * rather than comparing the post-clip list size to maxResults, which fires a
 * false-positive when the actual count exactly equals the cap.
 */
public record SearchResult(List<SearchMatch> matches, int totalEncountered) {

    public boolean truncated() {
        return totalEncountered > matches.size();
    }
}
