package com.example.nested;

import java.util.List;

public class Flows {

    static final List<? extends Number> NUMBERS = List.of(1, 2);

    static class Box<T> {
        T value;
    }

    static int first() {
        return NUMBERS.get(0).intValue() + NUMBERS.subList(0, 1).size();
    }

    int peek(Box<? extends CharSequence> box) {
        return box.value.length();
    }

    int measure(List<? extends CharSequence> texts, List<? super String> sink, List<?> any) {
        CharSequence head = texts.get(0);
        Object slot = sink.get(0);
        int size = any.subList(0, 1).size();
        return head.length() + size + slot.hashCode();
    }

    List<Runnable> queue() {
        return List.of(new Runnable() { public void run() { } });
    }

    void shout(List<? extends CharSequence> texts) {
        texts.forEach(text -> {
            System.out.println(text.length());
        });
    }

    void list(List<? extends CharSequence> texts) {
        for (var text : texts) {
            System.out.println(text.length());
        }
    }

    void launch() {
        var task = new Runnable() {
            public void run() {
            }
        };
        task.run();
    }
}
