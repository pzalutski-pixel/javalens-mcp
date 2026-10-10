package com.example.more;

public class Pair {

    static int pick(int first, int second) {
        return first - second;
    }

    int nested() {
        return pick(pick(1, 2), 3);
    }
}
