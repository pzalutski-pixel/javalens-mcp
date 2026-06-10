package com.reach;

import org.junit.jupiter.api.Test;

/**
 * doublesInput exercises TestedOnly.onlyFromTest directly; viaHelper reaches
 * it transitively through the non-test helper(). Imports are unresolved (no
 * JUnit dependency) — test detection is by annotation simple name.
 */
public class TestedOnlyTest {

    @Test
    void doublesInput() {
        TestedOnly t = new TestedOnly();
        assert t.onlyFromTest(2) == 4;
    }

    @Test
    void viaHelper() {
        assert helper() == 6;
    }

    private int helper() {
        return new TestedOnly().onlyFromTest(3);
    }
}
