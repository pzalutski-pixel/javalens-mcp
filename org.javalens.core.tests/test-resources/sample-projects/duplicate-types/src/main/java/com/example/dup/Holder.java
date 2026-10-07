package com.example.dup;

public class Holder {

    static class Part {
        int first() {
            return 1;
        }
    }

    static class Part {
        int second() {
            return 2;
        }
    }
}
