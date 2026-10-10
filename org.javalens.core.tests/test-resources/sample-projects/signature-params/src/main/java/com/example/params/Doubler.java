package com.example.params;

public class Doubler implements Scaler {

    @Override
    public int apply(int factor) {
        int twice = factor * 2;
        return twice + factor - factor;
    }
}
