package com.example.keys;

import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;

public class Dispatch {

    void send(String message) {
        class Courier {
            void deliver(String text) {
                System.out.println(text);
            }
        }
        new Courier().deliver(message);
    }

    Function<String, Integer> parser() {
        return new Function<String, Integer>() {
            public Integer apply(String text) {
                return Integer.valueOf(text);
            }
        };
    }

    void store(String value) {
        Map.Entry<String, String> entry = new AbstractMap.SimpleEntry<>("key", "none");
        entry.setValue(value);
    }
}
