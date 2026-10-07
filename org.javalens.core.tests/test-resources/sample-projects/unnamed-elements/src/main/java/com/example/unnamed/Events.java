package com.example.unnamed;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Events {

    static int total;

    static final Runnable RESET = () -> record("reset");

    static {
        record("loaded");
    }

    static void record(String event) {
        total += event.length();
    }

    abstract static class Sink {
        abstract void accept(String event);
    }

    static class Recorder extends Sink {
        void accept(String event) {
            record(event);
        }
    }

    Runnable listener() {
        return new Runnable() {
            int calls;

            public void run() {
                calls++;
            }
        };
    }

    List<String> names() {
        return new ArrayList<String>() {
            private static final long serialVersionUID = 1L;

            public boolean add(String name) {
                record(name);
                return super.add(name);
            }
        };
    }

    Sink sink() {
        return new Sink() {
            void accept(String event) {
                total++;
            }
        };
    }

    Supplier<String> later() {
        return () -> {
            String text = "later";
            record(text);
            return text;
        };
    }

    int tally() {
        class Counter {
            int value;

            void bump() {
                value++;
            }

            void merge(Counter[] others) {
                value += others.length;
            }
        }
        Counter counter = new Counter();
        counter.bump();
        counter.merge(new Counter[0]);
        return counter.value;
    }

    void audit() {
        class Auditor {
            void check() {
                record("check");
            }
        }
        new Auditor().check();
    }

    static int weigh(String text) {
        return text.length();
    }

    List<Object> pair() {
        Comparable<String> first = new Comparable<String>() {
            public int compareTo(String other) {
                return weigh(other);
            }
        };
        Supplier<Integer> second = new Supplier<Integer>() {
            public Integer get() {
                return weigh("second");
            }
        };
        return List.of(first, second);
    }

    List<Object> deferred() {
        Supplier<Object> pending = () -> new Object() {
            public String toString() {
                return "deferred" + weigh("deferred");
            }
        };
        Object now = new Object() {
            public int hashCode() {
                return weigh("now");
            }
        };
        return List.of(pending, now);
    }
}
