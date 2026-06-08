package com.example.cyc.a;

import module java.base;
import com.example.cyc.b.B;

/// Part of a deliberate package cycle (com.example.cyc.a &lt;-&gt; com.example.cyc.b)
/// that also carries a Java 25 module import, so find_circular_dependencies can be
/// checked: the real cross-package cycle must still be detected, and the module
/// import must not add a spurious java.base package to the graph.
public class A {

    public List<String> names() {
        return List.of("a");
    }

    public B makeB() {
        return new B();
    }
}
