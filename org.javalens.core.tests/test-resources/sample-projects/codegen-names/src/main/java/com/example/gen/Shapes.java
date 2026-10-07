package com.example.gen;

public class Shapes {

    public record Size(int width, int height) {

        public int area() {
            return width * height;
        }
    }

    public int total(int count) {
        record Run(int start, int end) {
        }
        Run run = new Run(0, count);
        return run.end() - run.start();
    }
}
