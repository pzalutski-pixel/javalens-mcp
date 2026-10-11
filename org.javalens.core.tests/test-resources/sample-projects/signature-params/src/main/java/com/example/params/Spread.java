package com.example.params;

public class Spread {
    int join(String... parts) {
        return parts.length;
    }

    long total(int xs[]) {
        return xs.length;
    }

    int both() {
        return join(new String[] {"a", "b"});
    }
}
