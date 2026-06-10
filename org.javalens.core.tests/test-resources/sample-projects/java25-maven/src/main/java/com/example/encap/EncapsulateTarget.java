package com.example.encap;

/// Target for encapsulate_field: a public field read and written directly by
/// EncapsulateReader, so the refactoring must generate the accessor pair AND
/// rewrite the external accesses.
public class EncapsulateTarget {

    public int count;

    public int twice() {
        return count * 2;
    }
}
