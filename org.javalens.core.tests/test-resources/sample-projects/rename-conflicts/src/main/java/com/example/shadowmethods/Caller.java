package com.example.shadowmethods;

public class Caller {
    void m() {
    }

    class Inner extends Callee {
        void k() {
            m();
        }
    }
}
