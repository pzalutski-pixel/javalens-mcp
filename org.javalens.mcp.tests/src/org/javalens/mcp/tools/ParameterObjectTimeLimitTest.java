package org.javalens.mcp.tools;

import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Pins, at both seams, that an introduce_parameter_object call cancelled at any of its
 * cancellation checks answers exactly TIMEOUT, leaves no working copy, and leaves the next call
 * the answer a fresh load gives. Its calls make the most checks of the refactorings, so it has a
 * class of its own beside {@link RefactoringTimeLimitTest}. The fixture is described in
 * {@link TimeLimitCalls}.
 */
class ParameterObjectTimeLimitTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final TimeLimitCalls calls = new TimeLimitCalls(helper);

    @Test
    @DisplayName("introduce_parameter_object: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void introduceParameterObject_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertRefactoringSweep("introduce_parameter_object", calls.position("Ring.java", 12, 16));
    }
}
