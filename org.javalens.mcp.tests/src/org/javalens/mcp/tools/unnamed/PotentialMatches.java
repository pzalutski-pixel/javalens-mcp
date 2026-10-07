package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;

/**
 * The potential-matches fixture and the payload pieces its tests share.
 *
 * <p>Package com.example.potential declares {@code Store} (field {@code count}, {@code fill(int)}
 * writing {@code count = Math.max(amount, 0)}, {@code level()} reading {@code count}), the
 * interface {@code Probe} ({@code probe()}), the exception {@code Fault} and the annotation type
 * {@code Marker}. {@code Client}, annotated {@code @Marker}, implements {@code Probe}; its
 * {@code use(Store store, Object item) throws Fault} calls {@code store.fill(1)}, writes
 * {@code store.count = 2}, takes {@code store::fill} as an {@code IntConsumer} and calls its
 * {@code accept(5)}, creates, casts, tests with instanceof and names as a type argument
 * {@code Store}, and catches {@code Fault} around {@code risky()}, calling
 * {@code all.get(0).fill(6)} in the handler; {@code read(Field, Object)} calls
 * {@code field.get(target)}, and {@code measure(String)} calls {@code text.length()}.
 *
 * <p>Package com.example.stray imports nothing from com.example.potential and imports
 * {@code org.absent.Ledger}, which no classpath entry declares. {@code Stray} repeats Client's
 * uses: {@code Marker}, {@code Probe}, {@code Store}, {@code Fault} name types the compiler
 * cannot find there, and every call, field access and method reference has a receiver of the
 * missing type {@code Ledger}. JDT's search reports each such place as a potential match
 * ({@code SearchMatch.A_INACCURATE}): MethodLocator.resolveLevel(MessageSend) :798-810 (no
 * binding, or no resolved type with the pattern's number of arguments), resolveLevel(
 * ReferenceExpression) :849-852, FieldLocator.matchField :109-110 (a field reference without a
 * binding), TypeReferenceLocator.resolveLevel(TypeReference) :758-768 through
 * PatternLocator.resolveLevelForType :825-827 (an invalid type binding), and
 * SuperTypeReferenceLocator.resolveLevel(ASTNode) :115. The qualified name {@code ledger.count}
 * is no match at all: its field part has no binding to compare (FieldLocator.resolveLevel(
 * NameReference) :371-398 returns IMPOSSIBLE_MATCH).
 *
 * <p>Drift (com.example.stray) is annotated {@code @Entity} and {@code @Component}, and its
 * {@code list()} {@code @GetMapping}, all without imports; the fixture declares those annotation
 * types (jakarta.persistence.Entity, org.springframework.stereotype.Component,
 * org.springframework.web.bind.annotation.GetMapping) in their own packages.
 *
 * <p>Search reports documents by path (MatchLocator.locateMatches :1459-1464), each in source
 * order. Positions are zero-based.
 */
final class PotentialMatches {

    static final String FIXTURE = "potential-matches";
    static final String STORE = "src/main/java/com/example/potential/Store.java";
    static final String PROBE = "src/main/java/com/example/potential/Probe.java";
    static final String CLIENT = "src/main/java/com/example/potential/Client.java";
    static final String STRAY = "src/main/java/com/example/stray/Stray.java";
    static final String DRIFT = "src/main/java/com/example/stray/Drift.java";

    static final String PACKAGE = "com.example.potential.";

    private PotentialMatches() {
    }

    /** A match location as search-based tools format it, its raw offset left out. */
    static Map<String, Object> at(String file, int line, int column, int length, String context) {
        return map("filePath", file, "length", length, "line", line, "column", column, "context", context);
    }

    /** A potential-matches block. */
    static Map<String, Object> potential(int count, List<Map<String, Object>> locations) {
        return map("count", count, "locations", locations);
    }

    /**
     * {@code payload} without raw offsets: the {@code offset} of every object that also has a
     * {@code length} (a formatted match), and every {@code startOffset} and {@code endOffset}.
     */
    static UnaryOperator<JsonNode> withoutOffsets() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            strip(copy);
            return copy;
        };
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("offset") && object.has("length")) {
                object.remove("offset");
            }
            object.remove("startOffset");
            object.remove("endOffset");
        }
        node.forEach(PotentialMatches::strip);
    }
}
