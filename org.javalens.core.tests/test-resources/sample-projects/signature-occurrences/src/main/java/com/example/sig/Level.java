package com.example.sig;

public enum Level {

    LOW(1), HIGH(5) {
        @Override
        int weight() {
            return 10;
        }
    };

    private final int base;

    Level(int base) {
        this.base = base;
    }

    int weight() {
        return base;
    }
}
