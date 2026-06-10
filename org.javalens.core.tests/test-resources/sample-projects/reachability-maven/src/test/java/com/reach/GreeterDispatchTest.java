package com.reach;

import org.junit.jupiter.api.Test;

/**
 * Exercises EnglishGreeter.greet (and transitively prefix) only through the
 * Greeter interface — covers the override edge in reverse test discovery.
 */
public class GreeterDispatchTest {

    @Test
    void greetsThroughInterface() {
        Greeter g = new EnglishGreeter();
        assert g.greet("x").startsWith("Hello");
    }
}
