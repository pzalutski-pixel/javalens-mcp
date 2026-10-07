package com.example.semantics;

import java.util.function.Supplier;

public class Calls {

    static class Probe {
        Probe(String label) {
        }
    }

    static String tag(String text) {
        return text;
    }

    static int sample(String text) {
        return text.length();
    }

    static int weigh(String text) {
        return text.length();
    }

    static int audit(String text) {
        return text.length();
    }

    static int stamp(String text) {
        return text.length();
    }

    int direct(String seed) {
        Supplier<Integer> deferred = () -> sample(seed);
        Probe probe = new Probe(tag(seed)) {
            final int base = weigh(seed);

            int measure() {
                return base;
            }
        };
        class Local {
            int count() {
                Supplier<Integer> inner = () -> audit(seed);
                return inner.get();
            }
        }
        Supplier<Probe> nested = () -> new Probe(label(seed)) {
            int measure() {
                return stamp(seed);
            }
        };
        return deferred.get() + new Local().count();
    }

    static String label(String text) {
        return text;
    }
}
