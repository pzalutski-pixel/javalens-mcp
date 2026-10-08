package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.javalens.core.Deadline;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.TimeoutSetting;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool calls under a time limit for the time-limit tests: loads with JAVALENS_TIMEOUT_SECONDS
 * set to a chosen value, deadlines that expire at a chosen cancellation check, never on the clock,
 * the exact TIMEOUT answer, and the sweep that cancels a call at each of its checks.
 *
 * <p>The deadline-calls fixture: Shape.java declares {@code area()} (line 5, column 11), which
 * Canvas.java calls on lines 17, 18 and 27; Circle.java's field {@code radius} (line 5, column
 * 11) is written and read in Canvas.main; Draft.java has one compile error, on line 6. The
 * refactoring targets: Figure.label() (line 5, column 18) to push down to Ring; Ring.width()
 * (line 8, column 15) to pull up to Figure; Ring.resize(int, int) (line 12, column 16) for a
 * parameter object; Gallery.Frame (line 8, column 24) to move to its own file; Gallery.banner()
 * (line 16, column 18) for a new superclass; and Gallery.titles()'s index loop to convert.
 */
final class TimeLimitCalls {

    static final String FIXTURE = "deadline-calls";
    static final String HINT = "Narrow the request, or raise JAVALENS_TIMEOUT_SECONDS "
        + "(5 to 300 seconds; unset means no limit) and restart JavaLens";

    private final TestProjectHelper helper;
    final ObjectMapper mapper = new ObjectMapper();
    /** Every deadline the service under test has started since the last {@link #expireAt}. */
    final List<Deadline> started = new ArrayList<>();

    TimeLimitCalls(TestProjectHelper helper) {
        this.helper = helper;
    }

    /** Load {@code fixture} with JAVALENS_TIMEOUT_SECONDS as {@code timeoutValue} (null: unset). */
    JdtServiceImpl load(String fixture, String timeoutValue) throws CoreException {
        JdtServiceImpl service = new JdtServiceImpl();
        service.setTimeoutSetting(TimeoutSetting.of(timeoutValue));
        service.loadProject(helper.getFixturePath(fixture));
        return service;
    }

    /** From now on, each call's deadline expires at its {@code check}-th cancellation check. */
    void expireAt(JdtServiceImpl service, long check) {
        started.clear();
        service.setDeadlines(seconds -> {
            Deadline deadline = Deadline.atCheck(seconds, check);
            started.add(deadline);
            return deadline;
        });
    }

    List<Boolean> expiredOfStarted() {
        return started.stream().map(Deadline::expired).toList();
    }

    /** The exact answer of a call to {@code tool} past its limit of {@code seconds}. */
    JsonNode timeout(String tool, int seconds) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", "TIMEOUT");
        error.put("message", "Operation '" + tool + "' timed out after " + seconds + " seconds");
        error.put("hint", HINT);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("success", false);
        payload.set("error", error);
        return payload;
    }

    String file(String name) {
        return helper.getFixturePath(FIXTURE).resolve("src/main/java/com/deadline/" + name).toString();
    }

    ObjectNode args(String name, String value) {
        ObjectNode args = mapper.createObjectNode();
        args.put(name, value);
        return args;
    }

    ObjectNode position(String fileName, int line, int column) {
        ObjectNode args = args("filePath", file(fileName));
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    /**
     * Assert, at both seams, that the call answers as a fresh load does under a deadline that never
     * expires and under one that expires one check past its last; and that for each of its
     * cancellation checks, a deadline expiring at that check makes it answer exactly TIMEOUT and
     * leave no working copy, and the next call, with no deadline expiring, answers as a fresh load
     * does. Each cancelled or counted call starts from the state a load leaves; the next call starts
     * from the state the cancelled one left.
     *
     * @return the fresh answer
     */
    JsonNode assertEachCancellationLeavesTheFreshAnswer(String tool, ObjectNode args) throws Exception {
        return assertEachCancellationLeavesTheFreshAnswer(tool, args, UnaryOperator.identity());
    }

    /**
     * {@link #assertEachCancellationLeavesTheFreshAnswer(String, ObjectNode)}, comparing answers as
     * {@code comparable} renders them.
     */
    JsonNode assertEachCancellationLeavesTheFreshAnswer(String tool, ObjectNode args, UnaryOperator<JsonNode> comparable)
            throws Exception {
        JsonNode fresh = comparable.apply(new EnvelopeHarness(load(FIXTURE, null)).assertEnvelopeFidelity(tool, args));
        // This load removes the fresh load's project.
        JdtServiceImpl service = load(FIXTURE, "30");
        EnvelopeHarness envelope = new EnvelopeHarness(service);

        expireAt(service, Long.MAX_VALUE);
        List<JsonNode> counted = bothSeamsFromLoadState(service, envelope, tool, args).stream().map(comparable).toList();
        long checks = started.get(0).checks();

        List<JsonNode> timeouts = List.of(timeout(tool, 30), timeout(tool, 30));
        List<String> failures = new ArrayList<>();
        for (long k = 1; k <= checks; k++) {
            expireAt(service, k);
            List<JsonNode> cancelled = bothSeamsFromLoadState(service, envelope, tool, args);
            if (!timeouts.equals(cancelled)) {
                failures.add("check " + k + ": answered " + cancelled);
            }
            if (!expiredOfStarted().equals(List.of(true, true))) {
                failures.add("check " + k + ": the deadlines at the two seams expired " + expiredOfStarted());
            }
            List<String> workingCopies = workingCopies();
            if (!workingCopies.isEmpty()) {
                failures.add("check " + k + ": working copies remain " + workingCopies);
            }
            expireAt(service, Long.MAX_VALUE);
            JsonNode next = comparable.apply(envelope.assertEnvelopeFidelity(tool, args));
            if (!fresh.equals(next)) {
                failures.add("check " + k + ": the next call answered " + next);
            }
        }

        expireAt(service, checks + 1);
        List<JsonNode> pastLastCheck =
            bothSeamsFromLoadState(service, envelope, tool, args).stream().map(comparable).toList();
        assertAll(
            () -> assertTrue(checks > 0, "the call makes cancellation checks; it made " + checks),
            () -> assertEquals(List.of(fresh, fresh), counted, "under a deadline that never expires"),
            () -> assertEquals(List.of(), failures),
            () -> assertEquals(List.of(fresh, fresh), pastLastCheck,
                "under a deadline expiring one check past the last the call makes"),
            () -> assertEquals(List.of(), workingCopies()));
        return fresh;
    }

    /** The sweep, for a call whose fresh answer is a change, not a refusal. */
    void assertRefactoringSweep(String tool, ObjectNode args) throws Exception {
        JsonNode fresh = assertEachCancellationLeavesTheFreshAnswer(tool, args);
        assertEquals(true, fresh.at("/success").asBoolean(), () -> "the fresh answer: " + fresh);
    }

    /**
     * The call through the JSON-RPC envelope, then through execute(), each from one state: no
     * project graph built, no source element open, and no index work pending, so each makes the
     * same cancellation checks every time.
     */
    private List<JsonNode> bothSeamsFromLoadState(JdtServiceImpl service, EnvelopeHarness envelope, String tool,
                                                  ObjectNode args) throws Exception {
        toLoadState(service);
        JsonNode viaEnvelope = envelope.payload(tool, args);
        toLoadState(service);
        JsonNode viaExecute = mapper.readTree(mapper.writeValueAsString(
            envelope.registry().getTool(tool).orElseThrow().execute(args.deepCopy())));
        return List.of(viaEnvelope, viaExecute);
    }

    private static void toLoadState(JdtServiceImpl service) throws CoreException {
        service.getProjectGraphService().invalidate();
        for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                root.close();
            }
        }
        new SearchEngine().searchAllTypeNames(null, SearchPattern.R_EXACT_MATCH, null, SearchPattern.R_EXACT_MATCH,
            IJavaSearchConstants.TYPE,
            SearchEngine.createJavaSearchScope(new IJavaElement[] { service.getJavaProject() }),
            new TypeNameRequestor() { }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
    }

    /** The primary working copies open in the workspace, by name. */
    static List<String> workingCopies() {
        return Arrays.stream(JavaCore.getWorkingCopies(null)).map(ICompilationUnit::getElementName).sorted().toList();
    }
}
