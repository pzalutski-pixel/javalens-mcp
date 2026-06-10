package com.reach;

/**
 * hook() is called through a Base-typed reference that always holds a Child,
 * so Child.hook() is reachable only via the override edge. Base itself is
 * never instantiated.
 */
public class Base {

    public String hook() {
        return "base";
    }
}
