package com.example.modular;

/**
 * Sample type inside a JPMS module. Existence verifies JavaLens loads source
 * files inside a module-declaring project, not just the module descriptor.
 */
public class Hub {
    public int counter;

    public int read() {
        return counter;
    }
}
