package org.javalens.mcp.tools.unnamed;

/**
 * The unnamed-semantics fixture (package com.example.semantics; positions zero-based).
 *
 * <ul>
 *   <li>Calls.java: {@code Calls} declares the member class {@code Probe} with the constructor
 *       {@code Probe(String)} (line 7, column 8); the static methods {@code tag} (line 11, column
 *       18), {@code sample} (line 15), {@code weigh} (line 19), {@code audit} (line 23) and
 *       {@code stamp} (line 27), each named at column 15 but tag; and {@code direct(String seed)}
 *       (line 31, column 8). direct calls {@code sample} in a lambda (line 32); creates anonymous
 *       class 1, a Probe, passing {@code tag(seed)} (line 33), whose field {@code base} is
 *       initialized by {@code weigh(seed)} (line 34) and whose {@code measure()} (line 36, column
 *       16) returns it; declares the local class {@code Local}, whose {@code count()} (line 41,
 *       column 16) calls {@code audit} in a lambda (line 42) and that lambda's {@code get()} (line
 *       43); creates, in a lambda, anonymous class 2, a Probe passed {@code label(seed)} (line
 *       46), whose {@code measure()} (line 47, column 16) calls {@code stamp} (line 48); and calls
 *       {@code deferred.get()} and {@code new Local().count()} (line 51). Then the static method
 *       {@code label} (line 54, column 18). tag, sample, weigh, audit, stamp and label call only
 *       {@code String.length()}, or nothing.</li>
 *   <li>Overrides.java: {@code Overrides} declares the member interface {@code Shape} with
 *       {@code name()} and the abstract member class {@code Base} with {@code size()};
 *       {@code square()} returns anonymous class 1, a Shape, whose {@code name()} is named at line
 *       16, column 26; {@code later()} returns a lambda creating anonymous class 2, a Base, whose
 *       {@code size()} is named at line 24, column 16; {@code order()} returns anonymous class 3,
 *       a {@code Comparable<String>}, whose {@code compareTo(String)} is named at line 32, column
 *       23.</li>
 *   <li>Shadow.java: {@code Shadow} declares the member class {@code Local} (line 4, column 17)
 *       whose {@code depth()} is named at line 5, column 12; {@code probe()} declares the local
 *       class {@code Local} (line 11, column 14) whose {@code depth()} is named at line 12, column
 *       16, and calls it; {@code measure()} (line 19) calls the member class's {@code depth()};
 *       {@code runner()} returns anonymous class 1, a Runnable whose {@code run()} (line 25,
 *       column 24) calls measure; {@code nest()} declares the local class {@code Holder} (line 32,
 *       column 14) with the member class {@code Inner} (line 33, column 18), whose {@code depth()}
 *       is named at line 34, column 20. ShadowTest.java (test sources): {@code @Test measures()}
 *       (line 7) calls measure, {@code @Test probes()} (line 12) calls probe.</li>
 *   <li>Routes.java: {@code Routes}, {@code @Path("/routes")}; {@code fallback()} returns
 *       anonymous class 1, an Object subclass whose {@code hidden()} is {@code @GET
 *       @Path("/hidden")}; {@code deferred()} returns a lambda creating anonymous class 2, an
 *       Object subclass whose {@code later()} is {@code @GET @Path("/later")}; then
 *       {@code @GET @Path("/list") list()} (line 33), {@code @GET @Path("/count") count()} (line
 *       39) and {@code @POST @Path("/add") add()} (line 45); {@code scoped()} declares the local
 *       class {@code Scoped}, {@code @Path("/scoped")}, with {@code @GET @Path("/inside")
 *       inside()}. Then its member types, each with its own {@code @Path}: the inner class
 *       {@code Inner} ("/inner", {@code @GET @Path("/nested") nested()}), the record
 *       {@code Ledger} ("/ledger", {@code @GET @Path("/entry") entry()}, line 74), the enum
 *       {@code Level} ("/level", {@code @POST @Path("/current") current()}, line 85) and the
 *       static class {@code Archive} ("/archive", {@code @GET @Path("/all") all()}, line 94, and
 *       {@code @POST @Path("/store") store()}, line 100); and the inner class {@code Holder}
 *       ("/outer") with the static member class {@code Deep} ("/deep", {@code @GET
 *       @Path("/leaf") leaf()}, line 111).</li>
 *   <li>Shapes.java: {@code Shapes} declares the member class {@code Figure} with the field
 *       {@code corners} (line 5, column 12) and {@code sides()} (line 7, column 12), extended by
 *       the member class {@code Square}; the member class {@code Plain} with {@code weight()}
 *       (line 16, column 12); {@code triangle()} and {@code circle()} return anonymous classes 1
 *       and 2, both Figures; {@code light()} returns anonymous class 3, a Plain; {@code board()}
 *       returns anonymous class 4, a Board. Panel.java: {@code Panel} declares only
 *       {@code widen()} (line 4, column 9) after a blank line; Board.java: {@code Board extends
 *       Panel}, an empty body closing on line 3.</li>
 *   <li>Annotation declarations stand in for jakarta.ws.rs (GET, POST, Path) and JUnit
 *       (Test).</li>
 * </ul>
 *
 * <p>JDT numbers anonymous classes per enclosing type in source order, lambda bodies included;
 * a local class is named after its enclosing type ({@code Calls$Local}).
 */
final class UnnamedSemantics {

    static final String FIXTURE = "unnamed-semantics";
    static final String SOURCES = "src/main/java/com/example/semantics/";
    static final String CALLS = SOURCES + "Calls.java";
    static final String OVERRIDES = SOURCES + "Overrides.java";
    static final String SHADOW = SOURCES + "Shadow.java";
    static final String ROUTES = SOURCES + "Routes.java";
    static final String SHAPES = SOURCES + "Shapes.java";
    static final String PANEL = SOURCES + "Panel.java";
    static final String BOARD = SOURCES + "Board.java";
    static final String SHADOW_TEST = "src/test/java/com/example/semantics/ShadowTest.java";

    static final String PACKAGE = "com.example.semantics.";

    private UnnamedSemantics() {
    }
}
