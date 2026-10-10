package com.example.sig;

/**
 * Doubles with {@link #twice(int)}, as {@link Calc#twice(int)} says.
 */
public class Calc {

    public static int twice(int value) {
        return value * 2;
    }

    public static int add(int value) {
        return value + 1;
    }

    public static int add(int value, int step) {
        return value + step;
    }
}
