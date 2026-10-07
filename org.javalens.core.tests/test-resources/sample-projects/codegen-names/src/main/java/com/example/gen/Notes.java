package com.example.gen;

public class Notes {

    private String last;

    void log(String text) {
        last = text;
    }

    void write() {
        log("start");
        last = null;
    }
}
