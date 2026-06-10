package com.example.encap;

/// External reader/writer of EncapsulateTarget.count — its direct accesses
/// must be rewritten to accessor calls by encapsulate_field.
public class EncapsulateReader {

    public int bump(EncapsulateTarget target) {
        target.count = target.count + 1;
        return target.count;
    }
}
