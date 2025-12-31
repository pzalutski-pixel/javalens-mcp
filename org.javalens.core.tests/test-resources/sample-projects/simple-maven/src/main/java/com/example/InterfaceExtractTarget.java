package com.example;

import java.util.List;

/**
 * Test fixture for extract interface tool.
 * Contains public methods that can be extracted to interface.
 */
public class InterfaceExtractTarget implements Comparable<InterfaceExtractTarget> {

    private String name;
    private int value;

    /**
     * Constructor - should not be included in interface.
     */
    public InterfaceExtractTarget(String name) {
        this.name = name;
        this.value = 0;
    }

    /**
     * Public method - should be included in interface.
     */
    public String getName() {
        return name;
    }

    /**
     * Public method - should be included in interface.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Public method - should be included in interface.
     */
    public int getValue() {
        return value;
    }

    /**
     * Public method with parameters - should be included.
     */
    public void process(String input, int count) {
        this.name = input;
        this.value = count;
    }

    /**
     * Public method with exception - should be included with throws.
     */
    public void validate() throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name cannot be null");
        }
    }

    /**
     * Public method with return type - should be included.
     */
    public List<String> getItems() {
        return List.of(name);
    }

    /**
     * Private method - should NOT be included in interface.
     */
    private void helper() {
        System.out.println("helper");
    }

    /**
     * Static method - should NOT be included in interface.
     */
    public static InterfaceExtractTarget create(String name) {
        return new InterfaceExtractTarget(name);
    }

    /**
     * Protected method - should NOT be included (only public).
     */
    protected void protectedMethod() {
        System.out.println("protected");
    }

    /**
     * Override from Comparable - may or may not include.
     */
    @Override
    public int compareTo(InterfaceExtractTarget other) {
        return this.name.compareTo(other.name);
    }

    /**
     * Override from Object - should NOT be included.
     */
    @Override
    public String toString() {
        return name + ": " + value;
    }
}
