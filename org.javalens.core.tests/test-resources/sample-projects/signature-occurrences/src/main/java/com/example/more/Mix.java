package com.example.more;

/**
 * Mixes with {@link #mix(int a, String s)}, as {@link #mix(int, String)} does.
 */
public class Mix {

    public static int mix(int a, String s) {
        return a + s.length();
    }

    int used = mix(1, "x");
}
