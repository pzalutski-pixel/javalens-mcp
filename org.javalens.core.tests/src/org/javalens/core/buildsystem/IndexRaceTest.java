package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug F — JDT search index race.
 *
 * <p>The original {@code JdtServiceImpl.loadProject} returned as soon as the {@code IJavaProject}
 * was configured, but JDT builds its search index lazily on a background job. Callers who issued
 * a {@code SearchEngine} query immediately after {@code loadProject} sometimes saw zero results
 * — not because the symbol didn't exist, but because the indexer hadn't finished yet.
 *
 * <p>The fix forces the index to be ready before {@code loadProject} returns by issuing a
 * dummy {@code searchAllTypeNames} call with {@code WAIT_UNTIL_READY_TO_SEARCH}.
 *
 * <p>This test loads a freshly-copied fixture (unique workspace project name per iteration so
 * each repetition starts with an unindexed project) and immediately queries for a known type.
 * Without the fix, some repetitions would race and return zero hits. With the fix, all 20
 * repetitions return at least one hit.
 */
class IndexRaceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @RepeatedTest(20)
    @DisplayName("search immediately after loadProject returns hits — no indexer race")
    void searchAfterLoadIsSynchronous() throws Exception {
        // Fresh copy each iteration so the workspace project name differs and the index
        // genuinely rebuilds. Otherwise prior iterations would warm the index and mask the bug.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");

        List<SearchMatch> matches = service.getSearchService()
            .searchSymbols("Calculator", IJavaSearchConstants.TYPE, 10);

        // Strict checks. The previous !isEmpty() assertion would pass against a buggy
        // implementation that leaked matches from a prior load, or that returned the wrong
        // type with a similar name. The fixture has exactly one Calculator type, declared in
        // Calculator.java — pin both invariants.
        assertEquals(1, matches.size(),
            "Expected exactly 1 'Calculator' type after loadProject. Empty list means the "
                + "JDT search index was not yet ready (Bug F). A >1 result means stale "
                + "matches leaked across loads. Got: " + matches.size());
        String matchPath = matches.get(0).getResource() != null
            ? matches.get(0).getResource().getFullPath().toString() : "";
        assertTrue(matchPath.endsWith("Calculator.java"),
            "Expected the single match to be Calculator.java; got match resource: "
                + matchPath);
    }
}
