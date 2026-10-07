package com.example.nested;

public class Owner {

    Runnable first() {
        return new Runnable() {
            public void run() {
            }
        };
    }

    Base second() {
        return new Base() {
            void tune() {
            }
        };
    }

    Root third() {
        class Twig extends Root {
        }
        return new Twig();
    }
}
