package com.example.semantics;

public class Shadow {

    static class Local {
        int depth() {
            return 1;
        }
    }

    int probe() {
        class Local {
            int depth() {
                return 2;
            }
        }
        return new Local().depth();
    }

    int measure() {
        return new Local().depth();
    }

    Runnable runner() {
        return new Runnable() {
            public void run() {
                measure();
            }
        };
    }

    int nest() {
        class Holder {
            class Inner {
                int depth() {
                    return 3;
                }
            }
        }
        return new Holder().new Inner().depth();
    }
}
