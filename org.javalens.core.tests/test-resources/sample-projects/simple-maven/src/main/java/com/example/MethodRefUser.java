package com.example;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Fixture for find_method_references positive case.
 * References MethodRefTarget.formatId via the Type::method syntax,
 * its instance method greet via bound instance::method, and its
 * constructor via Type::new.
 */
public class MethodRefUser {

    public String use(int id) {
        IntFunction<String> formatter = MethodRefTarget::formatId;
        return formatter.apply(id);
    }

    public String useBound(MethodRefTarget instance, String name) {
        Function<String, String> bound = instance::greet;
        return bound.apply(name);
    }

    public MethodRefTarget useCtor() {
        Supplier<MethodRefTarget> factory = MethodRefTarget::new;
        return factory.get();
    }
}
