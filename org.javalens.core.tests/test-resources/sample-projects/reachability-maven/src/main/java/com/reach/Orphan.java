package com.reach;

/**
 * Entirely unreachable: no entry point or test references this type. The
 * internal deadMethod -> deadChain edge exists in the graph but must not make
 * either method reachable (closure-leak isolation case).
 */
public class Orphan {

    public static final String DEAD_CONSTANT = "dead";

    public void deadMethod() {
        deadChain();
    }

    void deadChain() {
    }
}
