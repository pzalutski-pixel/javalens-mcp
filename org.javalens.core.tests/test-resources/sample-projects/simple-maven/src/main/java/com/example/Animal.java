package com.example;

/**
 * Test fixture for super method testing.
 */
public class Animal {

    public void speak() {
        System.out.println("Animal speaks");
    }

    public void move() {
        System.out.println("Animal moves");
    }
}

/**
 * Dog extends Animal and overrides speak().
 */
class Dog extends Animal {

    @Override
    public void speak() {
        System.out.println("Dog barks");
    }
}
