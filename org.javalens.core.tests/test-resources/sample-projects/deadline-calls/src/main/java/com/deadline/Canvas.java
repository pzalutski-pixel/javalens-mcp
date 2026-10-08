package com.deadline;

import java.util.ArrayList;
import java.util.List;

/** Totals the area of its shapes; main is the entry point the graph reaches from. */
public class Canvas {

    private final List<Shape> shapes = new ArrayList<>();

    public void add(Shape shape) {
        shapes.add(shape);
    }

    public double totalArea() {
        double total = 0;
        for (Shape shape : shapes) {
            if (shape.area() > 0) {
                total += shape.area();
            }
        }
        return total;
    }

    public double largest() {
        double largest = 0;
        for (Shape shape : shapes) {
            largest = Math.max(largest, shape.area());
        }
        return largest;
    }

    public static void main(String[] args) {
        Canvas canvas = new Canvas();
        canvas.add(new Circle(1));
        canvas.add(new Square(2));
        Circle unit = new Circle(1);
        unit.radius = 2;
        System.out.println(canvas.totalArea() + unit.radius);
    }
}
