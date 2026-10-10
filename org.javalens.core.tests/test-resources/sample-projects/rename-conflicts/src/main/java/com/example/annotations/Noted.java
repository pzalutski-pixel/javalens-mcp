package com.example.annotations;

public class Noted {
    String note() {
        return "n";
    }

    @Tag(text = "x")
    void marked() {
    }
}
