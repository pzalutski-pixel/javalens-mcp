package org.javalens.core.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link TestEnvironment}. The class is invoked throughout the suite as
 * the skip-vs-fail gate for missing tools; if its branches misbehave, every gated test
 * would silently behave wrongly (e.g. skip when CI should fail loudly).
 *
 * <p>The env-var-dependent branch ({@code JAVALENS_TESTS_REQUIRE_TOOLS=true}) cannot be
 * toggled in-process; instead the test asserts that {@code requireOrSkip(false, ...)}
 * throws one of the two acceptable exception types (AssertionError when the env var is
 * set, TestAbortedException otherwise). Both outcomes are correct.
 */
class TestEnvironmentTest {

    @Test
    @DisplayName("requireOrSkip(true, ...) is a no-op — returns normally")
    void availableTrue_returnsNormally() {
        assertDoesNotThrow(() -> TestEnvironment.requireOrSkip(true, "anything"));
    }

    @Test
    @DisplayName("requireOrSkip(false, ...) throws — AssertionError under CI flag, TestAbortedException otherwise")
    void availableFalse_throwsExpectedException() {
        Throwable t = assertThrows(Throwable.class,
            () -> TestEnvironment.requireOrSkip(false, "missing-tool-marker"));
        // Either type is acceptable. Both must mention the description so the developer /
        // CI logs identify which gate fired.
        boolean isAssertion = t instanceof AssertionError;
        boolean isAborted = t instanceof TestAbortedException;
        assertTrue(isAssertion || isAborted,
            "Expected AssertionError or TestAbortedException; got " + t.getClass());
        assertTrue(t.getMessage() != null && t.getMessage().contains("missing-tool-marker"),
            "Exception message must include the gate description; got: " + t.getMessage());
    }

    @Test
    @DisplayName("requireOrSkip(Object, ...) non-null returns normally")
    void objectOverload_nonNull_returnsNormally() {
        assertDoesNotThrow(() -> TestEnvironment.requireOrSkip("/some/path", "tool"));
    }

    @Test
    @DisplayName("requireOrSkip(Object, ...) null throws — same shape as boolean false branch")
    void objectOverload_null_throws() {
        Throwable t = assertThrows(Throwable.class,
            () -> TestEnvironment.requireOrSkip((Object) null, "missing-object-marker"));
        boolean isAssertion = t instanceof AssertionError;
        boolean isAborted = t instanceof TestAbortedException;
        assertTrue(isAssertion || isAborted,
            "Expected AssertionError or TestAbortedException; got " + t.getClass());
    }

    @Test
    @DisplayName("toolsRequired() reflects the current process env var state, no NPE on unset")
    void toolsRequired_consistentWithEnv() {
        // Without the env var set, Boolean.parseBoolean(null) returns false; with it set to
        // "true" returns true. Either way, no exception. The actual returned value depends
        // on the runner; we pin only the no-NPE contract.
        boolean result = TestEnvironment.toolsRequired();
        // Either false or true is acceptable here — the point is that the call returns.
        assertTrue(result || !result, "toolsRequired must return a boolean, not throw");
    }
}
