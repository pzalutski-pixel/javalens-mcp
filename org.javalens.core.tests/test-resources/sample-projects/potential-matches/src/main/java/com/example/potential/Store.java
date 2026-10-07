package com.example.potential;

public class Store {

    public int count;

    public void fill(int amount) {
        count = Math.max(amount, 0);
    }

    public int level() {
        return count;
    }
}
