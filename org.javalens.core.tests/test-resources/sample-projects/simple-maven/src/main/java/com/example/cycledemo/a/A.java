package com.example.cycledemo.a;

import com.example.cycledemo.b.B;

/**
 * Fixture for find_circular_dependencies positive case.
 * Forms a cycle a -> b -> a with sibling B.
 */
public class A {
    public B getB() {
        return new B();
    }
}
