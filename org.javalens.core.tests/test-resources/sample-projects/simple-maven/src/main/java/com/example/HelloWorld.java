package com.example;

/**
 * Basic hello world class for testing.
 */
public class HelloWorld {
    private String greeting;

    /**
     * Creates a new HelloWorld with default greeting.
     */
    public HelloWorld() {
        this.greeting = "Hello, World!";
    }

    /**
     * Creates a new HelloWorld with custom greeting.
     * @param greeting the greeting message
     */
    public HelloWorld(String greeting) {
        this.greeting = greeting;
    }

    /**
     * Gets the greeting message.
     * @return the greeting
     */
    public String getGreeting() {
        return greeting;
    }

    /**
     * Sets the greeting message.
     * @param greeting the new greeting
     */
    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    /**
     * Prints the greeting to stdout.
     */
    public void printGreeting() {
        System.out.println(greeting);
    }

    /**
     * Main entry point.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        HelloWorld hello = new HelloWorld();
        hello.printGreeting();
    }
}
