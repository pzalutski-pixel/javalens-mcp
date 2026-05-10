package com.example;

/**
 * Fixture for constructor signature change testing.
 */
public class ConstructorTarget {
    private final String value;

    public ConstructorTarget(String value) {
        this.value = value;
    }

    public String getValue() { return value; }
}
