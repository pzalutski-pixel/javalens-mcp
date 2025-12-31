package com.example;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;

/**
 * Test fixture for refactoring tools.
 * Contains code patterns for extract, inline, rename operations.
 */
public class RefactoringTarget {

    // Field for rename testing
    private String userName;
    private int count;

    // Constant candidate
    private static final int MAX_SIZE = 100;

    /**
     * Method with extractable expressions.
     */
    public void processData(String input) {
        // Variable to inline
        String trimmed = input.trim();
        System.out.println(trimmed);
        System.out.println(trimmed.length());

        // Expression to extract as variable
        int result = input.length() * 2 + 10;
        System.out.println(result);

        // Expression to extract as constant
        String prefix = "PREFIX_";
        System.out.println(prefix + input);
    }

    /**
     * Method with extractable code block.
     */
    public int calculateTotal(List<Integer> numbers) {
        // Block to extract as method
        int sum = 0;
        for (Integer num : numbers) {
            sum += num;
        }
        // End of extractable block

        return sum * 2;
    }

    /**
     * Simple method for inlining.
     */
    private int doubleValue(int value) {
        return value * 2;
    }

    /**
     * Method that calls doubleValue for inline testing.
     */
    public int processValue(int x) {
        int doubled = doubleValue(x);
        return doubled + 1;
    }

    /**
     * Method with parameters for signature change.
     */
    public String formatMessage(String message, int count) {
        return message + ": " + count;
    }

    /**
     * Method that calls formatMessage.
     */
    public void printMessages() {
        String result = formatMessage("Items", 5);
        System.out.println(result);
        System.out.println(formatMessage("Total", 10));
    }

    /**
     * Method with local variable for rename.
     */
    public void localVariableRename() {
        int oldName = 42;
        System.out.println(oldName);
        int doubled = oldName * 2;
        System.out.println(doubled);
    }

    /**
     * Method for field rename testing.
     */
    public void useFields() {
        userName = "test";
        count = 10;
        System.out.println(userName + ": " + count);
    }

    /**
     * Getter for rename testing.
     */
    public String getUserName() {
        return userName;
    }

    /**
     * Setter for rename testing.
     */
    public void setUserName(String userName) {
        this.userName = userName;
    }

    /**
     * Method with modified variable (should refuse inline).
     */
    public void modifiedVariable() {
        int value = 10;
        value = value + 5;
        System.out.println(value);
    }

    /**
     * Method with no initializer (should refuse inline).
     */
    public void noInitializer(int param) {
        int value;
        value = param;
        System.out.println(value);
    }
}
