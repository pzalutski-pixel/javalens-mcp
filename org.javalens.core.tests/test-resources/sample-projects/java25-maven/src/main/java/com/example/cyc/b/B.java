package com.example.cyc.b;

import com.example.cyc.a.A;

/// The reciprocal half of the com.example.cyc.a &lt;-&gt; com.example.cyc.b cycle.
public class B {

    public A makeA() {
        return new A();
    }
}
