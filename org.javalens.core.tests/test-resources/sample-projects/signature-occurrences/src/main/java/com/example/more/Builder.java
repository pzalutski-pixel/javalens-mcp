package com.example.more;

public class Builder {

    private int total;

    public Builder with(int value) {
        total += value;
        return this;
    }

    int built() {
        return new Builder().with(1).with(2).total;
    }
}
