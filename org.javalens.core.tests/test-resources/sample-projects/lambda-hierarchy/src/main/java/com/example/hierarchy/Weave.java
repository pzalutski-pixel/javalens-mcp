package com.example.hierarchy;

public class Weave {

    interface Knot {
        void tie();
    }

    static class Loop {
        public void tie() {
        }
    }

    static class Hitch extends Loop implements Knot {
        public void tie() {
        }
    }

    interface Bend {
        void join(String rope);
    }

    static class Splice {
        public void join(String cord) {
        }
    }

    static class Sheet extends Splice implements Bend {
    }

    interface Hook {
        void hang(String coat);
    }

    static class Peg<T> {
        public void hang(T coat) {
        }
    }

    static class Rack extends Peg<String> implements Hook {
    }

    static void fasten(Knot knot, Loop loop) {
        knot.tie();
        loop.tie();
    }

    static void link(Bend bend, Sheet sheet) {
        bend.join("a");
        sheet.join("b");
    }
}
