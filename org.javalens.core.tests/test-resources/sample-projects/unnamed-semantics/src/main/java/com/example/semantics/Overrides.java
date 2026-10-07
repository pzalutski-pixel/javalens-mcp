package com.example.semantics;

import java.util.function.Supplier;

public class Overrides {

    interface Shape {
        String name();
    }

    abstract static class Base {
        abstract int size();
    }

    Shape square() {
        return new Shape() {
            public String name() {
                return "square";
            }
        };
    }

    Supplier<Base> later() {
        return () -> new Base() {
            int size() {
                return 1;
            }
        };
    }

    Comparable<String> order() {
        return new Comparable<String>() {
            public int compareTo(String other) {
                return 0;
            }
        };
    }
}
