package com.example;

/**
 * Record implementing {@link Greeter}. find_implementations on Greeter
 * must surface this record kind as an implementer.
 */
public record GreetingRecord(String prefix) implements Greeter {
    @Override
    public String greet() {
        return prefix + ", world.";
    }
}
