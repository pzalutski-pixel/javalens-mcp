package com.example;

/**
 * Enum implementing {@link Greeter}. find_implementations on Greeter
 * must surface this enum kind as an implementer. Override sits on the
 * enum itself (no constant-specific bodies) to keep the implementers
 * set down to exactly {enum, record} and isolate the kind-dimension
 * being tested.
 */
public enum GreetingMode implements Greeter {
    FORMAL,
    INFORMAL;

    @Override
    public String greet() {
        return this == FORMAL ? "Good day." : "Hey.";
    }
}
