package com.example;

import java.util.function.IntFunction;

/**
 * Fixture for find_method_references positive case.
 * References MethodRefTarget.formatId via the Type::method syntax.
 */
public class MethodRefUser {

    public String use(int id) {
        IntFunction<String> formatter = MethodRefTarget::formatId;
        return formatter.apply(id);
    }
}
