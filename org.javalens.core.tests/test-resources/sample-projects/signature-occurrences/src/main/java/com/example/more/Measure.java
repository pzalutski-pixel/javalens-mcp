package com.example.more;

public class Measure {

    int total(Square square, Shape shape) {
        return square.area() + shape.area() + square.area();
    }
}
