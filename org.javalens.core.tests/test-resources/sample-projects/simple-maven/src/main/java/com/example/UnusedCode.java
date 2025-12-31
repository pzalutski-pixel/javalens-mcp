package com.example;

/**
 * Class containing unused code for testing FindUnusedCodeTool.
 */
public class UnusedCode {

    // Unused private field - should be detected
    private int unusedField;

    // Unused private field with initializer - should be detected
    private String unusedStringField = "never used";

    // Used private field - should NOT be detected
    private int usedField;

    // Public field - should NOT be detected (may be used externally)
    public int publicField;

    /**
     * Unused private method - should be detected.
     */
    private void unusedPrivateMethod() {
        System.out.println("This method is never called");
    }

    /**
     * Another unused private method - should be detected.
     */
    private int unusedPrivateMethodWithReturn() {
        return 42;
    }

    /**
     * Used private method - should NOT be detected.
     */
    private int usedPrivateMethod() {
        return usedField * 2;
    }

    /**
     * Public method that uses private members.
     */
    public int publicMethod() {
        usedField = 10;
        return usedPrivateMethod();
    }

    /**
     * Another public method - should NOT be detected.
     */
    public void anotherPublicMethod() {
        System.out.println("Public methods are not reported as unused");
    }

    /**
     * Protected method - should NOT be detected.
     */
    protected void protectedMethod() {
        System.out.println("Protected methods may be used by subclasses");
    }

    /**
     * Package-private method - should NOT be detected as unused.
     */
    void packagePrivateMethod() {
        System.out.println("Package-private methods may be used in same package");
    }
}
