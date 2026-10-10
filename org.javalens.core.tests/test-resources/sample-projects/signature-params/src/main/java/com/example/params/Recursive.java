package com.example.params;

public class Recursive {
    int count(int depth, int step) {
        return depth <= 0 ? 0 : count(depth - step, step);
    }
}
