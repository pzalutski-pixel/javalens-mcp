package com.example;

/**
 * Fixture for plain Java project (no pom.xml, no build.gradle).
 * Verifies LoadProjectTool detects the standard src/main/java layout
 * even without a build file.
 */
public class Hello {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
