package com.example.ctor;

/// JEP 513 flexible constructor body: a validation statement precedes the
/// explicit {@code super(v)} delegation, so the delegating call is not the
/// first statement of the constructor.
public class Derived extends Base {

    public Derived(int v) {
        if (v < 0) {
            throw new IllegalArgumentException();
        }
        super(v);
    }
}
