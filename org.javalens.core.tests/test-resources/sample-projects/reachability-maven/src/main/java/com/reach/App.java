package com.reach;

/**
 * Both field initializers run when the type is reached (the constructor is
 * implicit, so their edges are owned by the type node): greeter creates
 * EnglishGreeter, DEFAULT calls defaultName(). run() reads both fields and
 * dispatches through the Greeter interface.
 */
public class App {

    private static final String DEFAULT = defaultName();

    private final Greeter greeter = new EnglishGreeter();

    public String run(String name) {
        String target = name.isEmpty() ? DEFAULT : name;
        return greeter.greet(target);
    }

    private static String defaultName() {
        return "world";
    }
}
