package com.example.nested;

public class Outer {

    class Inner {
        int depth;

        public void descend() {
            depth++;
        }
    }

    static class Nested {
        public void settle() {
        }
    }

    record Point(int x, int y) {
        public int sum() {
            return x + y;
        }
    }

    public Object task() {
        class Step {
            public void advance() {
            }
        }
        return new Step();
    }
}
