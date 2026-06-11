package com.reach;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Exercises EnglishGreeter.greet (and transitively prefix) only through the
 * Greeter interface — covers the override edge in reverse test discovery.
 * disabledGreeting covers the same chain but is @Disabled: it still counts
 * as an entry-point root (referenced code is not dead) and must surface with
 * a disabled flag in affected-test results.
 */
public class GreeterDispatchTest {

    @Test
    void greetsThroughInterface() {
        Greeter g = new EnglishGreeter();
        assert g.greet("x").startsWith("Hello");
    }

    @Test
    @Disabled("covering but disabled")
    void disabledGreeting() {
        Greeter g = new EnglishGreeter();
        assert g.greet("y").isEmpty();
    }
}
