package com.example.cycledemo.b;

import com.example.cycledemo.a.A;

/**
 * Fixture for find_circular_dependencies positive case.
 * Forms a cycle a -> b -> a with sibling A.
 */
public class B {
    public A getA() {
        return new A();
    }
}
