package com.example.hierarchy;

public class Twins {

    Object first() {
        class Echo {
        }
        return new Echo();
    }

    Object second() {
        class Echo {
        }
        return new Echo();
    }
}
