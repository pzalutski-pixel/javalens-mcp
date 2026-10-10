package com.example.params;

public class Ruler implements Measure {
    @Override
    public long measure(int size) {
        return size;
    }

    long twice() {
        return measure(3) * 2;
    }
}
