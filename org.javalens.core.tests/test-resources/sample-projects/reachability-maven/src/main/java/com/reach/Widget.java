package com.reach;

/**
 * Declares an EXPLICIT constructor, so `new Widget()` edges target the
 * constructor node, not the type node — and compute() is covered only as a
 * method call. A type-level reverse closure that looks at the type node alone
 * sees neither, which is exactly the find_affected_tests/#32 shape: tests
 * exercise the class entirely through its members.
 */
public class Widget {

    public final int seed = 9;

    public Widget() {
    }

    public int compute(int x) {
        return x + 1;
    }
}
