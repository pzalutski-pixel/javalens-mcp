package com.example.hierarchy;

public class Chain {

    interface Sink<T> {
        void put(T item);
    }

    interface Pipe<T> extends Sink<T> {
        T take();
    }

    static class Base implements Sink<String> {
        public void put(String item) {
        }

        static int count() {
            return 0;
        }

        int weight() {
            return 1;
        }
    }

    static class Middle extends Base implements Pipe<String> {
        public String take() {
            return "";
        }

        public void put(String item, int times) {
        }
    }

    static class Leaf extends Middle {
        public void put(String item) {
        }

        static int count() {
            return 2;
        }

        int weight() {
            return 3;
        }
    }

    static class Ranked implements Comparable<Base> {
        public int compareTo(Base other) {
            return 0;
        }
    }
}
