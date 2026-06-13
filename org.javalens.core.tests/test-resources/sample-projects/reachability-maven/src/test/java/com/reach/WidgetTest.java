package com.reach;

import org.junit.jupiter.api.Test;

/**
 * Exercises Widget only through its members: the constructor and compute().
 * The Widget type node has no direct incoming edge — coverage flows to the
 * ctor and method nodes — so finding this test from the Widget type requires
 * aggregating the type's members.
 */
public class WidgetTest {

    @Test
    void computesViaMember() {
        Widget w = new Widget();
        assert w.compute(2) == 3;
    }
}
