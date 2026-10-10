package com.example.shadowmethods;

public class Outer {
    void m() {
    }

    class Inner extends Base {
        void f() {
            m();
        }
    }
}
