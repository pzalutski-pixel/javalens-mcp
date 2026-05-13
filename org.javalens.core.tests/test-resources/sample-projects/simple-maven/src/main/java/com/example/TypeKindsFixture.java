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
}
