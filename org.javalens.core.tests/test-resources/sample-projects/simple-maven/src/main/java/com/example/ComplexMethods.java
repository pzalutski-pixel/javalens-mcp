package com.example;

/**
 * Class containing methods with varying complexity for testing GetComplexityMetricsTool.
 */
public class ComplexMethods {

    /**
     * Low complexity method (CC = 1).
     * Simple linear flow with no branches.
     */
    public int lowComplexity(int x) {
        return x + 1;
    }

    /**
     * Medium complexity method (CC = 4).
     * Contains if statements and a loop.
     */
    public int mediumComplexity(int x) {
        int result = 0;

        if (x > 0) {
            result = x;
        } else if (x < 0) {
            result = -x;
        }

        for (int i = 0; i < x; i++) {
            result += i;
        }

        return result;
    }

    /**
     * High complexity method (CC > 10).
     * Contains nested conditions, loops, and switch statement.
     */
    public int highComplexity(int x, int y, int z) {
        int result = 0;

        // Nested if statements (adds to cognitive complexity too)
        if (x > 0) {
            if (y > 0) {
                if (z > 0) {
                    result = x + y + z;
                } else {
                    result = x + y;
                }
            } else {
                if (z > 0) {
                    result = x + z;
                } else {
                    result = x;
                }
            }
        }

        // Loop with conditions
        for (int i = 0; i < x; i++) {
            if (i % 2 == 0) {
                result += i;
            } else if (i % 3 == 0) {
                result -= i;
            }
        }

        // Switch statement
        switch (y % 4) {
            case 0:
                result *= 2;
                break;
            case 1:
                result *= 3;
                break;
            case 2:
                result /= 2;
                break;
            default:
                result = 0;
        }

        // While loop with break
        while (result > 0) {
            if (result > 100) {
                break;
            }
            result--;
        }

        return result;
    }

    /**
     * Method with high cognitive complexity due to nesting.
     * Cognitive complexity penalizes nesting more than cyclomatic.
     */
    public String highCognitiveComplexity(int level) {
        StringBuilder sb = new StringBuilder();

        if (level > 0) {                          // +1
            if (level > 1) {                      // +2 (nested)
                if (level > 2) {                  // +3 (deeply nested)
                    if (level > 3) {              // +4 (very deeply nested)
                        sb.append("deep");
                    }
                }
            }
        }

        for (int i = 0; i < level; i++) {         // +1
            for (int j = 0; j < i; j++) {         // +2 (nested)
                for (int k = 0; k < j; k++) {     // +3 (deeply nested)
                    sb.append(k);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Empty method (CC = 1).
     */
    public void emptyMethod() {
        // No code - minimum complexity
    }

    /**
     * Method with try-catch (adds to complexity).
     */
    public int methodWithExceptionHandling(String input) {
        int result = 0;

        try {
            result = Integer.parseInt(input);
            if (result < 0) {
                result = -result;
            }
        } catch (NumberFormatException e) {
            result = -1;
        } catch (NullPointerException e) {
            result = -2;
        }

        return result;
    }

    /**
     * Method with ternary operators.
     */
    public int methodWithTernary(int x, int y) {
        int a = x > 0 ? x : -x;
        int b = y > 0 ? y : -y;
        return a > b ? a : b;
    }
}
