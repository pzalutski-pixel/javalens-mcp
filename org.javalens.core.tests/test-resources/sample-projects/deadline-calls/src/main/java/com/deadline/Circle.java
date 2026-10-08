package com.deadline;

/** A circle; its radius is a field to encapsulate. */
public class Circle implements Shape {

    double radius;

    public Circle(double radius) {
        this.radius = radius;
    }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}
