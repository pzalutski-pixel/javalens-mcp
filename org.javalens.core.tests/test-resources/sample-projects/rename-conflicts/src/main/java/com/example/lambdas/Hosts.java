package com.example.lambdas;

public class Hosts {
    interface Mark {
        String tag();
    }

    static class Base {
        void a() {
        }
    }

    Runnable marks() {
        return () -> {
            Mark mark = new Mark() {
                public String tag() {
                    return "t";
                }

                String label() {
                    return "l";
                }
            };
            mark.tag();
            class Sub extends Base {
                void b() {
                }
            }
        };
    }
}
