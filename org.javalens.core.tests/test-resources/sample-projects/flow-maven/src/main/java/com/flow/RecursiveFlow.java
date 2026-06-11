package com.flow;

public class RecursiveFlow {

    public void spin() {
        String n = null;
        loop(n);
    }

    void loop(String v) {
        loop(v);
    }
}
