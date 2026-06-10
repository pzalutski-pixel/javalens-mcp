package com.reach;

/**
 * The only declared entry point. Reaches App (and through it the Greeter
 * dispatch chain) plus the Base/Child override pair.
 */
public class Main {

    public static void main(String[] args) {
        App app = new App();
        app.run(args.length > 0 ? args[0] : "world");
        Base b = Child.create();
        b.hook();
    }
}
