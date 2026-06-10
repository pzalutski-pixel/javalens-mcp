package com.example.hier;

/// Subclass: liftable() is a candidate to pull up into HierBase; movable()
/// in HierBase is a candidate to push down into this class.
public class HierChild extends HierBase {

    public int liftable() {
        return 42;
    }
}
