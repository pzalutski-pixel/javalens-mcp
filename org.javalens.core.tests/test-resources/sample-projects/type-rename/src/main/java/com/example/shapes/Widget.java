package com.example.shapes;

/**
 * A widget, built by {@link #of(String)}; its parts are {@link com.example.shapes.Widget.Part}.
 */
public class Widget {

    public static final String KIND = "widget";

    private final String name;

    public Widget() {
        this("plain");
    }

    public Widget(String name) {
        this.name = name;
    }

    public static Widget of(String name) {
        return new Widget(name);
    }

    public String name() {
        return name;
    }

    /** A part of a {@link Widget}. */
    public static class Part {

        Widget owner;
    }
}
