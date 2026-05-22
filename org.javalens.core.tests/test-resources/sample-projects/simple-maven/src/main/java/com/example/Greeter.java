package com.example;

/**
 * Interface intentionally implemented by both an enum and a record so
 * find_implementations can be exercised for non-class implementer kinds.
 */
public interface Greeter {
    String greet();
}
