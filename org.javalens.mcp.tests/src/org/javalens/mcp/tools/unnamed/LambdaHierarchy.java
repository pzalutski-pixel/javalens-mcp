package org.javalens.mcp.tools.unnamed;

import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;

/**
 * The lambda-hierarchy fixture (package com.example.hierarchy; positions zero-based).
 *
 * <ul>
 *   <li>Host.java: {@code Host} (line 5) declares the member interface {@code Mark} (line 7,
 *       column 14) with {@code tag()} (line 8, column 15) and the static member class
 *       {@code Plate} (line 11, column 17) with {@code size()} (line 12, column 12). {@code marks()}
 *       creates five anonymous classes, each named by the type after {@code new}: anonymous class
 *       1, a Mark (line 18, column 25; its {@code tag()} at line 19, column 26); anonymous class
 *       2, a Mark created in a lambda body (line 23, column 41; {@code tag()} at line 24, column
 *       26; its body closes at line 27, column 8); anonymous class 3, a Mark (line 28, column 25;
 *       {@code tag()} at line 29, column 26); anonymous class 4, an Object (line 33, column 27);
 *       anonymous class 5, a Mark (line 35, column 25; {@code tag()} at line 36, column 26).
 *       {@code plates()} declares, in a lambda body, the local class {@code Thin extends Plate}
 *       (line 45, column 18; {@code size()} at line 46, column 20), then directly the local class
 *       {@code Thick extends Plate} (line 52, column 14; {@code size()} at line 53, column 16), then
 *       anonymous class 6, a Plate (line 57, column 25; {@code size()} at line 58, column 16). The
 *       local variable {@code plain} is never read: Host.java compiles with one warning.</li>
 *   <li>Chain.java: {@code Chain} (line 2) declares the member interfaces {@code Sink<T>} (line
 *       4, column 14; {@code put(T item)} at line 5, column 13) and {@code Pipe<T> extends
 *       Sink<T>} (line 8, column 14; {@code take()} at line 9); the static member classes
 *       {@code Base implements Sink<String>} (line 12, column 17; {@code put(String)} at line 13,
 *       column 20, {@code static count()} at line 16, column 19, {@code weight()} at line 20,
 *       column 12), {@code Middle extends Base implements Pipe<String>} (line 25, column 17;
 *       {@code take()} at line 26 and the overload {@code put(String, int)} at line 30, column
 *       20), {@code Leaf extends Middle} (line 34, column 17; {@code put(String)} at line 35,
 *       column 20, {@code static count()} at line 38, column 19, {@code weight()} at line 42,
 *       column 12) and {@code Ranked implements Comparable<Base>} (line 47, column 17), which
 *       names Base only as a type argument.</li>
 *   <li>Locals.java: {@code Locals} (line 5) declares the member enum {@code Mode} (line 7,
 *       column 9) with the constants {@code PLAIN} and {@code LOUD} (line 9), whose body (opening
 *       at line 9, column 13; the model flags it an enum, SourceElementNotifier adding AccEnum to
 *       an enum constant's body) declares {@code shout()} (line 10, column 19), and Mode's own
 *       {@code shout()} (line 15, column 15); the annotation type {@code Flag} (line 20); the static
 *       member class {@code Named extends Chain.Base} (line 23, column 17; {@code put(String)} at
 *       line 24, column 20); {@code sinks()} (line 28), which declares the lambda {@code quiet}, a
 *       {@code Chain.Sink<String>} (line 29, its parameter {@code item} at column 35), the local class {@code Local extends Chain.Base}
 *       (line 31, column 14; {@code put(String)} at line 32, column 24), the local class
 *       {@code Deeper extends Local} (line 35, column 14), anonymous class 1, a Local created in a
 *       lambda body (line 37, column 42; {@code put(String)} at line 38, column 24) and anonymous
 *       class 2, a {@code Chain.Sink<String>} named from column 39 (line 41; {@code put(String)} at
 *       line 42, column 24); the member record {@code Pair} (line 48) and, in {@code pairs()}, the
 *       local record {@code Twin} (line 52). Locals.java compiles without warnings.</li>
 *   <li>Ledger.java: the top-level record {@code Ledger}, alone in its file.</li>
 *   <li>Twins.java: {@code Twins} declares a local class {@code Echo} in {@code first()} (line 5)
 *       and another in {@code second()} (line 11).</li>
 * </ul>
 *
 * <p>The model numbers anonymous classes per enclosing type in source order, lambda bodies
 * included ({@code Host$1} to {@code Host$6}, {@code Locals$1}, {@code Locals$2}); the body of
 * {@code LOUD} is Mode's first ({@code Locals$Mode$1}); a local class is named after its enclosing
 * type ({@code Host$Thin}, {@code Locals$Local}).
 */
final class LambdaHierarchy {

    static final String FIXTURE = "lambda-hierarchy";
    static final String SOURCES = "src/main/java/com/example/hierarchy/";
    static final String HOST = SOURCES + "Host.java";
    static final String CHAIN = SOURCES + "Chain.java";
    static final String LOCALS = SOURCES + "Locals.java";

    static final String PACKAGE = "com.example.hierarchy.";

    /** java.lang.Object as a type entry: a binary type, so no file and no line. */
    static final Map<String, Object> OBJECT = map("name", "Object", "qualifiedName", "java.lang.Object", "kind", "class");

    private LambdaHierarchy() {
    }

    /** A type entry with its file and the line its declaration starts at. */
    static Map<String, Object> type(String name, String typeQualifiedName, String kind, String file, int line) {
        return map("name", name, "qualifiedName", PACKAGE + typeQualifiedName, "kind", kind, "filePath", file,
            "line", line);
    }

    /** An anonymous class entry: named by its type-qualified name, a class. */
    static Map<String, Object> anonymous(String typeQualifiedName, String file, int line) {
        return type(typeQualifiedName, typeQualifiedName, "class", file, line);
    }
}
