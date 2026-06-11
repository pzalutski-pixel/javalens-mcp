package com.flow;

public class DeepNullFlow {

    public String top() {
        String start = null;
        return middle(start);
    }

    String middle(String mid) {
        return bottom(mid);
    }

    String bottom(String end) {
        return end.trim();
    }
}
