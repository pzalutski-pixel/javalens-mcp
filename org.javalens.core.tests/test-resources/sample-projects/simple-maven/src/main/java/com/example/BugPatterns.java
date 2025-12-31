package com.example;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Class containing various bug patterns for testing FindPossibleBugsTool.
 */
public class BugPatterns {

    /**
     * Empty catch block - should be detected as a bug.
     */
    public void emptyExceptionHandler() {
        try {
            Integer.parseInt("not a number");
        } catch (NumberFormatException e) {
            // Empty catch block - bad practice
        }
    }

    /**
     * String comparison using == instead of equals() - should be detected.
     */
    public boolean stringCompareWithOperator(String a, String b) {
        return a == b;  // Bug: should use .equals()
    }

    /**
     * Correct string comparison using equals().
     */
    public boolean stringCompareWithEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    private String lockObject = "lock";  // String field for sync detection

    /**
     * Synchronization on String variable - should be detected.
     */
    public void syncOnStringVariable() {
        synchronized (lockObject) {
            // Bug: synchronizing on String variable (interned strings are shared)
            System.out.println("synchronized");
        }
    }

    /**
     * Unclosed resource - should be detected.
     */
    public void unclosedResource() throws Exception {
        InputStream stream = new FileInputStream("test.txt");
        // Bug: stream is never closed
        stream.read();
    }

    /**
     * Proper resource handling with try-with-resources.
     */
    public void properResourceHandling() throws Exception {
        try (InputStream stream = new FileInputStream("test.txt")) {
            stream.read();
        }
    }

    /**
     * Multiple bugs in one method.
     */
    public boolean multipleBugs(String input) {
        try {
            synchronized (lockObject) {
                return input == "expected";
            }
        } catch (Exception e) {
            // Empty catch
        }
        return false;
    }
}
