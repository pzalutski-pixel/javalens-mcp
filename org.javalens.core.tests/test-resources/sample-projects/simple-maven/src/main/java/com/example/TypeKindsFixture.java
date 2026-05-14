package com.example;

/**
 * Fixture covering type kinds get_type_at_position must report:
 * - enum (Color)
 * - generic class with type parameter (GenericContainer<T>)
 * - nested static class (Inner)
 */
public class TypeKindsFixture {

    /** Sample enum for kind="Enum" coverage. */
    public enum Color {
        RED, GREEN, BLUE
    }

    /** Sample generic class for typeParameters coverage. */
    public static class GenericContainer<T> {

        private T value;

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }

    /** Sample nested static class for declaringType + isNested coverage. */
    public static class Inner {

        public String label() {
            return "inner";
        }
    }

    /** Static method for modifier coverage. */
    public static String staticHelper(String prefix) {
        return prefix + ":static";
    }

    /** Synchronized method for modifier coverage. */
    public synchronized String synchronizedHelper(String input) {
        return input;
    }

    /** Generic method with its own type parameter U. */
    public <U> U convert(U value) {
        return value;
    }

    /** Method declaring `throws java.io.IOException`. */
    public String throwingHelper(String path) throws java.io.IOException {
        if (path == null) {
            throw new java.io.IOException("null");
        }
        return path;
    }

    /** Interface with a default method for default-modifier coverage. */
    public interface DefaultMethodHolder {
        default String greet() {
            return "hello";
        }
    }

    /** Protected field for protected-modifier coverage. */
    protected int protectedField;

    /** Transient field for transient-modifier coverage. */
    public transient String transientField;

    /** Volatile field for volatile-modifier coverage. */
    public volatile int volatileField;

    // Overloaded `greet` methods for get_signature_help overload coverage.

    public String greet() {
        return "hi";
    }

    public String greet(String name) {
        return "hi, " + name;
    }

    public String greet(String name, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append("hi, ").append(name).append('\n');
        }
        return sb.toString();
    }

    /** Generic class with bounded type parameter for type-parameter-bounds coverage. */
    public static class BoundedBox<N extends Number> {
        private N value;
        public N get() { return value; }
    }

    /**
     * Rich Javadoc fixture for get_javadoc tag-parsing coverage.
     *
     * Demonstrates parsing of every supported Javadoc tag.
     *
     * @param input the value to process
     * @param count number of iterations
     * @return formatted string built from input
     * @throws java.lang.IllegalArgumentException when input is null
     * @see com.example.Calculator
     * @since 1.0
     * @author JavaLens fixture
     * @version 2.5
     * @deprecated kept only for tag-parsing tests; not part of any real API
     */
    @Deprecated
    public String richlyDocumentedMethod(String input, int count) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(input);
        return sb.toString();
    }

    public void noJavadocMethod() {
    }
}
