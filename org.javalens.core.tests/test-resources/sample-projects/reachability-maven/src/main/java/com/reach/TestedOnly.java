package com.reach;

/**
 * Reachable only from test code: with main-only roots this is dead, with test
 * roots included it is alive.
 */
public class TestedOnly {

    public int onlyFromTest(int x) {
        return x * 2;
    }
}
