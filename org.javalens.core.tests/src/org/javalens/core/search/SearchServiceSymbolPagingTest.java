package org.javalens.core.search;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SearchService.searchSymbols checks every declaration the search reports against the
 * {@code ?} wildcard and the caller's filter before it counts or keeps any: the result holds
 * the passing declarations from {@code offset} on, at most {@code limit} of them, and
 * {@code totalEncountered} counts every passing declaration.
 *
 * <p>The symbol-paging fixture's Journal.java declares fourteen fields named log (Journal's own
 * and those of the nested Audit to Job, then Sink's enum constant, Recorder's and Session's
 * fields); Signals.java declares eleven records named *Signal, eleven classes, one more record,
 * an interface, an enum and an annotation type.
 */
class SearchServiceSymbolPagingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("a page of the fields named log: offset 10, limit 5 holds the last four of 14")
    void fieldPage_holdsTheDeclarationsFromOffset() throws Exception {
        SearchService search = helper.loadProject("symbol-paging").getSearchService();

        SearchResult page = search.searchSymbols("log", IJavaSearchConstants.FIELD, element -> true, 10, 5);

        assertAll(
            () -> assertEquals(List.of("Job.log", "Sink.log", "Recorder.log", "Session.log"), names(page)),
            () -> assertEquals(14, page.totalEncountered()));
    }

    @Test
    @DisplayName("the filter narrows before the page: records among *Signal, offset 11, are the twelfth record of 12")
    void filter_narrowsBeforeThePage() throws Exception {
        SearchService search = helper.loadProject("symbol-paging").getSearchService();

        SearchResult page = search.searchSymbols("*Signal", IJavaSearchConstants.TYPE,
            SearchServiceSymbolPagingTest::isRecord, 11, 1);

        assertAll(
            () -> assertEquals(List.of("Signals.WaveSignal"), names(page)),
            () -> assertEquals(12, page.totalEncountered()));
    }

    @Test
    @DisplayName("the ? wildcard narrows every declaration the broadened search reports: x? passes xa and xb behind 300 others")
    void questionMark_narrowsEveryReportedDeclaration() throws Exception {
        Path root = helper.copyFixture("symbol-paging");
        StringBuilder wide = new StringBuilder("package org.example.paging;\n\nclass Wide {\n");
        for (int i = 0; i < 300; i++) {
            wide.append(String.format("    int x%03d;\n", i));
        }
        wide.append("    int xa;\n    int xb;\n}\n");
        Files.writeString(root.resolve("src/main/java/org/example/paging/Wide.java"), wide.toString(),
            StandardCharsets.UTF_8);
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        SearchService search = service.getSearchService();

        SearchResult first = search.searchSymbols("x?", IJavaSearchConstants.FIELD, 1);
        SearchResult second = search.searchSymbols("x?", IJavaSearchConstants.FIELD, element -> true, 1, 1);

        assertAll(
            () -> assertEquals(List.of("Wide.xa"), names(first)),
            () -> assertEquals(2, first.totalEncountered()),
            () -> assertEquals(List.of("Wide.xb"), names(second)),
            () -> assertEquals(2, second.totalEncountered()));
    }

    private static boolean isRecord(IJavaElement element) {
        try {
            return element instanceof IType type && type.isRecord();
        } catch (JavaModelException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Each match as declaring type and name, or enclosing type and name for a nested type. */
    private static List<String> names(SearchResult result) {
        List<String> names = new ArrayList<>();
        for (SearchMatch match : result.matches()) {
            IMember member = (IMember) match.getElement();
            IType owner = member instanceof IType type ? type.getDeclaringType() : member.getDeclaringType();
            names.add(owner.getElementName() + "." + member.getElementName());
        }
        return names;
    }
}
