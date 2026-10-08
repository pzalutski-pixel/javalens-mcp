package com.deadline;

/** A ring between two radii; resize takes two parameters to bundle into one object. */
public class Ring extends Figure {

    private int inner;
    private int outer;

    public int width() {
        return outer - inner;
    }

    public void resize(int inner, int outer) {
        this.inner = inner;
        this.outer = outer;
    }

    public String describe() {
        return label() + " " + width();
    }
}
