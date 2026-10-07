package com.example.orphan;

public class Heir extends Orphan implements Comparable<Heir> {

    public int compareTo(Heir other) {
        return 0;
    }

    public int size() {
        return 1;
    }
}
