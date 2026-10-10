package com.example.sig;

public class Ops {

    Op inc = value -> value + 1;
    Op dec = (int value) -> value - 1;

    int run() {
        return inc.apply(1) + dec.apply(2);
    }
}
