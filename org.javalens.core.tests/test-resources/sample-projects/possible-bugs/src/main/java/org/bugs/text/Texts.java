package org.bugs.text;

public class Texts {

    private final String lock = "lock";

    boolean same(String a, String b) {
        return a == b;
    }

    void guarded() {
        synchronized (lock) {
            same("x", "y");
        }
    }

    int size() {
        String text = null;
        return text.length();
    }
}
