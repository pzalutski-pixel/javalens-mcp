package com.example.params;

public class Descend {
    int fall(int depth, int step, int unused) {
        return depth <= 0 ? 0 : fall(depth - step, step, depth);
    }
}
