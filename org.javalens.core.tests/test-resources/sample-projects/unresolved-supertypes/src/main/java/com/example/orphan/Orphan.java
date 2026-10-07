package com.example.orphan;

import org.absent.Base;
import org.absent.Shape;

public class Orphan extends Base implements Shape, Runnable {

    public void run() {
    }

    public int size() {
        return 0;
    }

    static int count() {
        return 2;
    }
}
