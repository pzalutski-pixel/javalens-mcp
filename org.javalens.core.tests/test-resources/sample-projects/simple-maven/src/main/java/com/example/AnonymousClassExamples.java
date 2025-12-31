package com.example;

import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Test fixture for convert anonymous to lambda tool.
 * Contains various anonymous class patterns.
 */
public class AnonymousClassExamples {

    private String instanceField = "instance";

    /**
     * Simple Runnable anonymous class - can convert.
     */
    public void simpleRunnable() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                System.out.println("Hello");
            }
        };
        runnable.run();
    }

    /**
     * Comparator anonymous class - can convert.
     */
    public void comparatorExample() {
        List<String> list = new ArrayList<>();
        Collections.sort(list, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.length() - b.length();
            }
        });
    }

    /**
     * Single parameter functional interface - can convert.
     */
    public void singleParam() {
        java.util.function.Consumer<String> consumer = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String s) {
                System.out.println(s);
            }
        };
        consumer.accept("test");
    }

    /**
     * Anonymous class with 'this' reference - should refuse.
     */
    public void withThisReference() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                System.out.println(this.toString());
            }
        };
        runnable.run();
    }

    /**
     * Anonymous class using outer 'this' - can convert.
     */
    public void withOuterThis() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                System.out.println(AnonymousClassExamples.this.instanceField);
            }
        };
        runnable.run();
    }

    /**
     * Anonymous class with multiple methods - should refuse.
     */
    public void multipleMethodsExample() {
        Object obj = new Object() {
            public void method1() {
                System.out.println("one");
            }
            public void method2() {
                System.out.println("two");
            }
        };
    }

    /**
     * Anonymous class with block body - can convert to block lambda.
     */
    public void blockBodyExample() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                String msg = "Hello";
                msg = msg + " World";
                System.out.println(msg);
            }
        };
        runnable.run();
    }

    /**
     * Anonymous class with return - can convert.
     */
    public void withReturn() {
        java.util.function.Supplier<String> supplier = new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                return "result";
            }
        };
        System.out.println(supplier.get());
    }

    /**
     * Non-functional interface - should refuse.
     */
    public void nonFunctionalInterface() {
        List<String> list = new ArrayList<String>() {
            @Override
            public boolean add(String s) {
                System.out.println("Adding: " + s);
                return super.add(s);
            }
        };
    }
}
