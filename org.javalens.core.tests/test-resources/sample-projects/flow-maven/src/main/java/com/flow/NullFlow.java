package com.flow;

public class NullFlow {

    public String entry(boolean flag) {
        String value = null;
        if (flag) {
            value = "set";
        }
        return describe(value);
    }

    String describe(String text) {
        return "len=" + text.length();
    }

    public int safeEntry() {
        String safe = "ok";
        return safe.length();
    }
}
