package com.example.keys;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApiTest {

    @Nested
    class OrdersTest {

        @Test
        void opens() {
            new Api.Orders().open();
        }
    }
}
