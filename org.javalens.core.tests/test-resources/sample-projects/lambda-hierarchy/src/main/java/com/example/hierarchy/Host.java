package com.example.hierarchy;

import java.util.List;
import java.util.function.Supplier;

public class Host {

    interface Mark {
        String tag();
    }

    static class Plate {
        int size() {
            return 0;
        }
    }

    List<Mark> marks() {
        Mark first = new Mark() {
            public String tag() {
                return "first";
            }
        };
        Supplier<Mark> later = () -> new Mark() {
            public String tag() {
                return "later";
            }
        };
        Mark third = new Mark() {
            public String tag() {
                return "third";
            }
        };
        Object plain = new Object() {
        };
        Mark fifth = new Mark() {
            public String tag() {
                return "fifth";
            }
        };
        return List.of(first, later.get(), third, fifth);
    }

    List<Plate> plates() {
        Supplier<Plate> deferred = () -> {
            class Thin extends Plate {
                int size() {
                    return 1;
                }
            }
            return new Thin();
        };
        class Thick extends Plate {
            int size() {
                return 2;
            }
        }
        Plate wide = new Plate() {
            int size() {
                return 3;
            }
        };
        return List.of(deferred.get(), new Thick(), wide);
    }
}
