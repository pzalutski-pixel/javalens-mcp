package com.example;

/**
 * Fixture for find_method_references positive case.
 * Exposes a static method that MethodRefUser consumes via Type::method syntax.
 */
public class MethodRefTarget {

    public static String formatId(int id) {
        return "ID:" + id;
    }
}
