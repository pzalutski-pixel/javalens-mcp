package com.example;

/**
 * Fixture that calls ConstructorTarget, for constructor call-site propagation testing.
 */
public class ConstructorCallers {
    public ConstructorTarget createDefault() {
        return new ConstructorTarget("default");
    }

    public ConstructorTarget createCustom(String name) {
        return new ConstructorTarget(name);
    }
}
