package com.example.params;

public class Guarded {
    int guard(final int limit, @Deprecated int floor) {
        return limit;
    }

    int use() {
        return guard(1, 2);
    }
}
