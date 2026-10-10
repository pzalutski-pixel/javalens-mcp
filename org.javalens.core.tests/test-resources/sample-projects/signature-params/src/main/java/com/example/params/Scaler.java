package com.example.params;

/**
 * Scales a number.
 */
public interface Scaler {

    /**
     * Applies the scale.
     *
     * @param factor the number to scale
     * @return the scaled number
     */
    int apply(int factor);
}
