package com.example;

public class FilledCircle implements IFillable {

    private final double radius;
    private final String fillColor;

    public FilledCircle(double radius, String fillColor) {
        this.radius = radius;
        this.fillColor = fillColor;
    }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }

    @Override
    public String getFillColor() {
        return fillColor;
    }
}
