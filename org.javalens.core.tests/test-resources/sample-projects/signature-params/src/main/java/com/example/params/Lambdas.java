package com.example.params;

/** See {@link Scaler#apply(int factor)}. */
public class Lambdas {

    Scaler triple = factor -> factor * 3;
    Scaler quad = (int n) -> n * 4;

    int use(Scaler scaler) {
        return scaler.apply(5) + triple.apply(6);
    }
}
