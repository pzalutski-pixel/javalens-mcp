package com.example.hierarchy;

import java.util.function.Supplier;

public class Kiln {

    static class Vessel {
    }

    static class Jar extends Vessel {
        int depth() {
            return 1;
        }
    }

    Supplier<Object> fire() {
        return () -> {
            class Urn extends Jar {
            }
            return new Urn();
        };
    }
}
