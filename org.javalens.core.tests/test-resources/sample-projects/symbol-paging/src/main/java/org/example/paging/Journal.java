package org.example.paging;

/**
 * Declarations named log of several kinds. Searched as fields, the first eleven are
 * static final constants, followed by an enum constant, a static field and an
 * instance field. A method, a parameter, a local variable and a record component
 * share the name without being field declarations.
 */
public class Journal {

    private static final Object log = new Object();

    void log(String message) {
        Object log = message;
        write(log);
    }

    static void write(Object log) {
    }

    static class Audit {
        private static final Object log = new Object();
    }

    static class Billing {
        private static final Object log = new Object();
    }

    static class Cache {
        private static final Object log = new Object();
    }

    static class Delivery {
        private static final Object log = new Object();
    }

    static class Export {
        private static final Object log = new Object();
    }

    static class Feed {
        private static final Object log = new Object();
    }

    static class Gateway {
        private static final Object log = new Object();
    }

    static class History {
        private static final Object log = new Object();
    }

    static class Inbox {
        private static final Object log = new Object();
    }

    static class Job {
        private static final Object log = new Object();
    }

    enum Sink {
        console,
        log
    }

    static class Recorder {
        static Object log;
    }

    static class Session {
        Object log;
    }

    record Line(String log) {
    }
}
