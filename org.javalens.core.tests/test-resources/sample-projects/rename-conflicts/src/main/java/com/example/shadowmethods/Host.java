package com.example.shadowmethods;

public class Host {
    void k() {
    }

    class Nested extends Other {
        void f() {
            k();
        }
    }
}
