package com.example;

/// JEP 513 flexible constructor body: a statement precedes the explicit super().
/// Used to check that add_throws targets the constructor signature, resolving the
/// enclosing constructor from a body line and inserting after the parameter list
/// regardless of the statement that precedes super().
public class FlexibleThrows {

    private final int value;

    public FlexibleThrows(int v) {
        if (v < 0) {
            throw new IllegalArgumentException();
        }
        super();
        this.value = v;
    }

    public int value() {
        return value;
    }
}
