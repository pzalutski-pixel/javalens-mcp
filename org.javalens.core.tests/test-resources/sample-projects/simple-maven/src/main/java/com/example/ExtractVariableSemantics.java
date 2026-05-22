package com.example;

/**
 * Test fixture for extract_variable across positions where extraction would
 * change evaluation semantics. Extracting an expression to a declaration
 * BEFORE its containing statement moves the evaluation point — for loop
 * conditions and short-circuit operands, that re-evaluation no longer
 * happens.
 */
public class ExtractVariableSemantics {

    /**
     * Loop condition: the expression `i < 10` is re-evaluated each iteration.
     * Hoisting it to a single `boolean cond = i < 10;` before the loop
     * captures the initial value once; the loop then either runs forever
     * (if true) or never enters (if false).
     */
    public int loopCondition(int seed) {
        int total = 0;
        for (int i = 0; i < 10; i++) {
            total += i + seed;
        }
        return total;
    }

    /**
     * Short-circuit RHS: `s.length()` only runs when `s != null`. Hoisting
     * it to a `int len = s.length();` line BEFORE the if would NPE on null
     * inputs.
     */
    public boolean shortCircuit(String s) {
        if (s != null && s.length() > 0) {
            return true;
        }
        return false;
    }

    /**
     * Ternary right branch: `risky.length()` runs only when `risky != null`.
     * Hoisting it loses the guard.
     */
    public int ternary(String risky) {
        int len = risky != null ? risky.length() : -1;
        return len;
    }

    /**
     * While loop: same shape as for-loop condition. The condition
     * `counter < target` must be re-checked each iteration.
     */
    public int whileLoop(int target) {
        int counter = 0;
        while (counter < target) {
            counter++;
        }
        return counter;
    }
}
