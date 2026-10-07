package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.tools.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unnamed-elements fixture and the exact-payload assertion its tests share.
 *
 * <p>Events.java (package com.example.unnamed) declares, in source order: the static field
 * {@code total}; the constant {@code RESET}, initialized by a lambda that calls
 * {@code record}; a static initializer calling {@code record}; {@code record(String)}; the
 * member classes {@code Sink} (abstract, with {@code accept(String)}) and {@code Recorder}
 * (extends Sink, its {@code accept} calls {@code record}); then three methods returning
 * anonymous classes: {@code listener()} a Runnable with the field {@code calls} (anonymous
 * class 1), {@code names()} an ArrayList subclass whose {@code add} calls {@code record} and
 * {@code super.add} (anonymous class 2), {@code sink()} a Sink whose {@code accept} writes
 * {@code total} (anonymous class 3); {@code later()} returning a lambda that declares
 * {@code text} and calls {@code record}; {@code tally()} declaring the local class
 * {@code Counter} (field {@code value}, methods {@code bump()} and {@code merge(Counter[])});
 * {@code audit()} declaring the local class {@code Auditor} whose {@code check()} calls
 * {@code record}; {@code weigh(String)}; {@code pair()} declaring two anonymous classes, a
 * Comparable and a Supplier (anonymous classes 4 and 5); {@code deferred()} declaring a lambda
 * whose body declares an anonymous Object subclass (anonymous class 6), then directly another
 * (anonymous class 7). The methods of anonymous classes 4 to 7 call {@code weigh}. Then
 * {@code label()}, Events' one public instance method; {@code span(List<? extends CharSequence>,
 * List<? super String>, List<?>)}, whose calls on its parameters capture their wildcards; and
 * {@code blanks()}, returning four one-line anonymous classes: a Runnable, an
 * {@code Iterable<String>}, a {@code ThreadLocal<String>} subclass and an Object subclass
 * (anonymous classes 8 to 11). Loose.java declares {@code Loose} in the default package.
 *
 * <p>JDT numbers anonymous classes per enclosing type in source order, lambda bodies included,
 * so the first three are {@code Events$1}, {@code Events$2} and {@code Events$3}; a local class
 * is named after its enclosing type, {@code Events$Counter}. Positions are zero-based.
 */
final class UnnamedElements {

    static final String FIXTURE = "unnamed-elements";
    static final String EVENTS = "src/main/java/com/example/unnamed/Events.java";
    static final String LOOSE = "src/main/java/Loose.java";

    static final String EVENTS_TYPE = "com.example.unnamed.Events";

    private UnnamedElements() {
    }

    /** An ordered map of alternating keys and values, for exact expected payloads. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** A success payload carrying {@code data} and, when not null, {@code meta}. */
    static Map<String, Object> success(Map<String, Object> data, Map<String, Object> meta) {
        Map<String, Object> payload = map("success", true, "data", data);
        if (meta != null) {
            payload.put("meta", meta);
        }
        return payload;
    }

    /** An INVALID_PARAMETER payload with the full message. */
    static Map<String, Object> invalidParameter(String message) {
        return map("success", false, "error", map("code", "INVALID_PARAMETER", "message", message));
    }

    /**
     * The payload of {@code tool} for {@code args}, through execute() and through the JSON-RPC
     * envelope, both equal {@code expected} after {@code normalize}. The execute() result is
     * compared as the JSON text it serializes to, as a client reads it.
     */
    static void assertPayload(ObjectMapper mapper, Tool tool, EnvelopeHarness envelope, ObjectNode args,
                              Map<String, Object> expected, UnaryOperator<JsonNode> normalize) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = normalize.apply(mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = normalize.apply(envelope.assertEnvelopeFidelity(tool.getName(), args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** {@link #assertPayload} with nothing normalized. */
    static void assertPayload(ObjectMapper mapper, Tool tool, EnvelopeHarness envelope, ObjectNode args,
                              Map<String, Object> expected) {
        assertPayload(mapper, tool, envelope, args, expected, UnaryOperator.identity());
    }

    /**
     * {@code payload} with the array at {@code path} (field names from the root) sorted by the
     * text of each element's {@code key} field.
     */
    static UnaryOperator<JsonNode> sortedBy(String key, String... path) {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            JsonNode node = copy;
            for (int i = 0; i < path.length - 1; i++) {
                node = node.path(path[i]);
            }
            if (!(node instanceof ObjectNode parent) || !(parent.get(path[path.length - 1]) instanceof ArrayNode array)) {
                return copy;
            }
            List<JsonNode> elements = new ArrayList<>();
            array.forEach(elements::add);
            elements.sort(Comparator.comparing(element -> element.path(key).asText()));
            ArrayNode sorted = parent.putArray(path[path.length - 1]);
            elements.forEach(sorted::add);
            return copy;
        };
    }

    /** {@code payload} with the field {@code field} removed from every element of the array at {@code path}. */
    static UnaryOperator<JsonNode> without(String field, String... path) {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            JsonNode node = copy;
            for (String name : path) {
                node = node.path(name);
            }
            node.forEach(element -> {
                if (element instanceof ObjectNode object) {
                    object.remove(field);
                }
            });
            return copy;
        };
    }
}
