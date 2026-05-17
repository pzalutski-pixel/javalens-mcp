package com.example;

/**
 * Fixture exercising Java 21 modern syntax that the analysis tools must
 * tolerate without throwing or misclassifying:
 *  - pattern matching for {@code instanceof} (with binding variable)
 *  - switch expression with type patterns
 *  - record deconstruction patterns
 *  - guarded patterns (`when` clause)
 *
 * <p>Consumed by {@code Java21SyntaxToleranceTest}. New patterns added here
 * MUST keep the line numbers of existing patterns stable — tests may pin
 * them. APPEND-ONLY.
 */
public class Java21Modern {

    // Sealed hierarchy for record-deconstruction patterns.
    public sealed interface Shape permits Square, Circle {}
    public record Square(int side) implements Shape {}
    public record Circle(double radius) implements Shape {}

    /** Pattern matching for {@code instanceof} with binding variable. */
    public int legacyVsPatternInstanceof(Object o) {
        // Legacy form — still supported.
        if (o instanceof String) {
            return ((String) o).length();
        }
        // Pattern form — binds `s` in scope of the if.
        if (o instanceof String s) {
            return s.length();
        }
        // Negated pattern — binding flows backward.
        if (!(o instanceof Integer n)) {
            return -1;
        }
        return n;
    }

    /** Switch expression with type patterns (Java 21). */
    public String describe(Object obj) {
        return switch (obj) {
            case null -> "null";
            case String s -> "string of length " + s.length();
            case Integer i -> "integer " + i;
            case int[] arr -> "int array of length " + arr.length;
            default -> "other: " + obj.getClass().getSimpleName();
        };
    }

    /** Switch expression with record deconstruction patterns (Java 21). */
    public double area(Shape shape) {
        return switch (shape) {
            case Square(int side) -> side * side;
            case Circle(double r) -> Math.PI * r * r;
        };
    }

    /** Guarded patterns (`when` clause). */
    public String classify(Object o) {
        return switch (o) {
            case Integer i when i < 0 -> "negative int";
            case Integer i when i == 0 -> "zero";
            case Integer i -> "positive int " + i;
            case null, default -> "non-int";
        };
    }
}
