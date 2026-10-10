package com.example.params;

public class Box {
    int width;

    Box(int width) {
        this.width = width;
    }

    static Box make() {
        return new Box(4);
    }
}
