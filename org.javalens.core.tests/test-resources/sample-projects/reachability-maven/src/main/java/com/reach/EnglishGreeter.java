package com.reach;

/**
 * greet() is never called directly — it is reachable only through the
 * Greeter.greet override edge. unusedPublicHelper() is dead public API.
 */
public class EnglishGreeter implements Greeter {

    @Override
    public String greet(String name) {
        return prefix() + name;
    }

    private String prefix() {
        return "Hello ";
    }

    public String unusedPublicHelper() {
        return "never called";
    }
}
