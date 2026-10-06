package org.example.kinds;

/**
 * Declarations of every kind: classes, an interface, an enum, a record and an annotation
 * type; instance, static and static final fields and enum constants; methods and
 * constructors. Parameters, local variables, record components, lambda expressions and a
 * method reference share the file without being field or method declarations.
 */
public class Catalog {

    private int count;

    static int created;

    public static final String LABEL = "catalog";

    final int width = 3;

    public Catalog() {
    }

    public Catalog(int count) {
        this.count = count;
    }

    public int count() {
        return count;
    }

    static void register(String name) {
        String entry = name;
        created += entry.length();
    }

    static class Note {
    }

    public interface Shelf {
        void place(Item item);
    }

    public enum Shade {
        LIGHT,
        DARK;

        private int depth;
    }

    public record Item(String title, int pages) {
        public Item {
        }

        public String summary() {
            return title + pages;
        }
    }

    public @interface Tag {
    }

    boolean test(String s) {
        java.util.function.Predicate<String> blank = text -> text.isBlank();
        java.util.function.Function<String, Integer> size = text -> text.length();
        java.util.function.Predicate<String> empty = String::isEmpty;
        return blank.test(s) || empty.test(s) || size.apply(s) > LABEL.length();
    }
}
