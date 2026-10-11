package com.example.params;

public class Area {
    int width;
    int height;

    int scaled(int k) {
        return k * width;
    }

    int area(int width) {
        return width * height;
    }

    void report(int k) {
        Runnable inner = new Runnable() {
            public void run() {
                int width = 0;
                System.out.println(k + width);
            }
        };
        Runnable later = () -> {
            int height = 1;
            System.out.println(k + height);
        };
        inner.run();
        later.run();
    }
}
