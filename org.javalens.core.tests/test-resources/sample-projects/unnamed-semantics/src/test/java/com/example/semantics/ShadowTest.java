package com.example.semantics;

import org.junit.jupiter.api.Test;

class ShadowTest {

    @Test
    void measures() {
        new Shadow().measure();
    }

    @Test
    void probes() {
        new Shadow().probe();
    }
}
