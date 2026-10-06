package org.example.capped;

/**
 * A method called four times from one method, then once from another.
 */
public class Dispatch {

    static int target() {
        return 1;
    }

    int first() {
        return target() + target() + target() + target();
    }

    int second() {
        return target();
    }
}
