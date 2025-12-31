package com.example;

/**
 * Simple calculator for testing search and navigation tools.
 */
public class Calculator {
    private int lastResult;

    /**
     * Adds two numbers.
     * @param a first operand
     * @param b second operand
     * @return the sum
     */
    public int add(int a, int b) {
        lastResult = a + b;
        return lastResult;
    }

    /**
     * Subtracts two numbers.
     * @param a first operand
     * @param b second operand
     * @return the difference
     */
    public int subtract(int a, int b) {
        lastResult = a - b;
        return lastResult;
    }

    /**
     * Multiplies two numbers.
     * @param a first operand
     * @param b second operand
     * @return the product
     */
    public int multiply(int a, int b) {
        lastResult = a * b;
        return lastResult;
    }

    /**
     * Gets the last calculation result.
     * @return the last result
     */
    public int getLastResult() {
        return lastResult;
    }
}
