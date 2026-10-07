package com.example.hierarchy;

import java.util.List;
import java.util.function.Supplier;

public class Locals {

    enum Mode {
        PLAIN,
        LOUD {
            String shout() {
                return "LOUD";
            }
        };

        String shout() {
            return "plain";
        }
    }

    @interface Flag {
    }

    static class Named extends Chain.Base {
        public void put(String item) {
        }
    }

    List<Chain.Sink<String>> sinks() {
        Chain.Sink<String> quiet = item -> {
        };
        class Local extends Chain.Base {
            public void put(String item) {
            }
        }
        class Deeper extends Local {
        }
        Supplier<Local> later = () -> new Local() {
            public void put(String item) {
            }
        };
        Chain.Sink<String> loose = new Chain.Sink<String>() {
            public void put(String item) {
            }
        };
        return List.of(quiet, new Deeper(), later.get(), loose);
    }

    record Pair(int left, int right) {
    }

    Object pairs() {
        record Twin(int left) {
        }
        return new Twin(1);
    }
}
