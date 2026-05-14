package com.example;

/**
 * Fixture for find_method_references positive case.
 * Exposes a static method that MethodRefUser consumes via Type::method syntax.
 */
public class MethodRefTarget {

    public static String formatId(int id) {
        return "ID:" + id;
    }

    /** Instance method exercised via bound method-reference (instance::greet). */
    public String greet(String name) {
        return "Hi, " + name;
    }

    /** Zero-arg constructor for Type::new constructor-reference coverage. */
    public MethodRefTarget() {
    }
}
