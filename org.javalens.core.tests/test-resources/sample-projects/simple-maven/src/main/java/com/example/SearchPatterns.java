package com.example;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Test fixture for fine-grained search tools.
 * Contains various code patterns for annotation, instantiation, cast,
 * instanceof, throws, catch, method reference, and type argument searches.
 */
@Deprecated
public class SearchPatterns implements Serializable {

    private static final long serialVersionUID = 1L;

    // Type arguments - List<String>, Map<String, Integer>
    private List<String> stringList;
    private Map<String, Integer> stringIntMap;
    private List<Calculator> calculatorList;

    /**
     * Method with annotation - Override.
     */
    @Override
    public String toString() {
        return "SearchPatterns";
    }

    /**
     * Deprecated method with annotation.
     */
    @Deprecated
    public void deprecatedMethod() {
        // Empty deprecated method
    }

    /**
     * Method with type instantiations.
     */
    public void createObjects() {
        // ArrayList instantiation
        List<String> list = new ArrayList<>();
        list.add("test");

        // HashMap instantiation
        Map<String, Integer> map = new HashMap<>();
        map.put("key", 1);

        // Calculator instantiation (project type)
        Calculator calc = new Calculator();
        int result = calc.add(1, 2);
    }

    /**
     * Method with type casts.
     */
    public void performCasts(Object obj) {
        // Cast to String
        if (obj instanceof String) {
            String str = (String) obj;
            System.out.println(str);
        }

        // Cast to List
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            System.out.println(list.size());
        }

        // Cast to Calculator
        if (obj instanceof Calculator) {
            Calculator calc = (Calculator) obj;
            calc.add(1, 2);
        }
    }

    /**
     * Method with instanceof checks.
     */
    public String checkTypes(Object obj) {
        // instanceof String
        if (obj instanceof String) {
            return "string";
        }

        // instanceof List
        if (obj instanceof List) {
            return "list";
        }

        // instanceof Calculator
        if (obj instanceof Calculator) {
            return "calculator";
        }

        // instanceof Serializable
        if (obj instanceof Serializable) {
            return "serializable";
        }

        return "unknown";
    }

    /**
     * Method that throws IOException.
     */
    public void readFile(String path) throws IOException {
        if (path == null) {
            throw new IOException("Path cannot be null");
        }
    }

    /**
     * Method that throws multiple exceptions.
     */
    public void riskyOperation(String input) throws IOException, IllegalArgumentException {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }
        if (input.isEmpty()) {
            throw new IOException("Input cannot be empty");
        }
    }

    /**
     * Method that throws RuntimeException.
     */
    public void validateInput(String input) {
        if (input == null) {
            throw new RuntimeException("Validation failed");
        }
    }

    /**
     * Method with catch blocks.
     */
    public void handleExceptions() {
        // Catch IOException
        try {
            readFile(null);
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }

        // Catch RuntimeException
        try {
            validateInput(null);
        } catch (RuntimeException e) {
            System.err.println("Runtime error: " + e.getMessage());
        }

        // Catch IllegalArgumentException
        try {
            riskyOperation(null);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
    }

    /**
     * Method with method references.
     */
    public void useMethodReferences() {
        List<String> items = new ArrayList<>();
        items.add("one");
        items.add("two");

        // Method reference to static method - String::valueOf
        Function<Integer, String> converter = String::valueOf;
        String result = converter.apply(42);

        // Method reference to instance method - System.out::println
        Consumer<String> printer = System.out::println;
        items.forEach(printer);

        // Method reference to constructor - ArrayList::new
        Supplier<List<String>> listFactory = ArrayList::new;
        List<String> newList = listFactory.get();

        // Method reference with comparator - String::compareToIgnoreCase
        items.sort(String::compareToIgnoreCase);
    }

    /**
     * Method demonstrating type arguments in various contexts.
     */
    public void useGenerics() {
        // List<String> type argument
        List<String> strings = new ArrayList<>();

        // Map<String, Integer> type arguments
        Map<String, Integer> counts = new HashMap<>();

        // Nested type arguments - List<Map<String, Integer>>
        List<Map<String, Integer>> nestedList = new ArrayList<>();

        // Bounded type - Comparator<String>
        Comparator<String> comp = String::compareTo;

        // Method with generic return - using Calculator in generics
        List<Calculator> calcs = new ArrayList<>();
        calcs.add(new Calculator());
    }

    /**
     * Inner class with annotations for additional testing.
     */
    @Deprecated
    public static class InnerClass {

        @Override
        public String toString() {
            return "InnerClass";
        }

        public Calculator createCalculator() {
            return new Calculator();
        }
    }
}
