package com.example.params;

public class Halver implements Scaler {

    @Override
    public int apply(int amount) {
        return amount / 2;
    }
}
