package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * search_symbols pages its results after every filter: the kind filter and the {@code ?}
 * wildcard narrow the declarations first, then {@code offset} and {@code maxResults} select the
 * page. {@code returned == min(maxResults, passing - offset)} (0 once offset reaches the passing
 * count), {@code meta.totalCount} is the passing count, and {@code pagination.hasMore} and
 * {@code meta.truncated} both equal {@code offset + returned < totalCount}.
 *
 * <p>The symbol-paging fixture orders its declarations so that the declarations a filter rejects
 * come first in search order (by file, then source order: a type's fields, then its member types
 * in turn). Journal.java declares fourteen fields named log: eleven static final constants,
 * then an enum constant, a static field and an instance field; a method, a parameter, a local
 * variable and a record component named log are not fields. Signals.java declares the types
 * named *Signal: eleven records, eleven classes, one more record, an interface, an enum and an
 * annotation type. Every expectation is the whole payload, through execute() and through the
 * JSON-RPC envelope.
 */
class SearchSymbolsPagingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String JOURNAL = "src/main/java/org/example/paging/Journal.java";
    private static final String SIGNALS = "src/main/java/org/example/paging/Signals.java";
    private static final String WIDE = "src/main/java/org/example/paging/Wide.java";
    private static final String PACKAGE = "org.example.paging";

    private static final List<String> NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    /** The fourteen fields named log, in search order. */
    private static final List<Map<String, Object>> LOG_FIELDS = List.of(
        field(JOURNAL, "log", "constant", 10, 32, "Object", "Journal"),
        field(JOURNAL, "log", "constant", 21, 36, "Object", "Journal$Audit"),
        field(JOURNAL, "log", "constant", 25, 36, "Object", "Journal$Billing"),
        field(JOURNAL, "log", "constant", 29, 36, "Object", "Journal$Cache"),
        field(JOURNAL, "log", "constant", 33, 36, "Object", "Journal$Delivery"),
        field(JOURNAL, "log", "constant", 37, 36, "Object", "Journal$Export"),
        field(JOURNAL, "log", "constant", 41, 36, "Object", "Journal$Feed"),
        field(JOURNAL, "log", "constant", 45, 36, "Object", "Journal$Gateway"),
        field(JOURNAL, "log", "constant", 49, 36, "Object", "Journal$History"),
        field(JOURNAL, "log", "constant", 53, 36, "Object", "Journal$Inbox"),
        field(JOURNAL, "log", "constant", 57, 36, "Object", "Journal$Job"),
        field(JOURNAL, "log", "enumConstant", 62, 8, "Sink", "Journal$Sink"),
        field(JOURNAL, "log", "field", 66, 22, "Object", "Journal$Recorder"),
        field(JOURNAL, "log", "field", 70, 15, "Object", "Journal$Session"));

    /** The eleven classes named *Signal, in source order: lines 41 to 71, every third line. */
    private static final List<Map<String, Object>> SIGNAL_CLASSES = signalTypes("class", 41, 17,
        "Lamp", "Morse", "Note", "Orb", "Ping", "Query", "Radio", "Siren", "Torch", "Urgent", "Voice");

    /** The eleven records named *Signal ahead of the classes: lines 8 to 38, every third line. */
    private static final List<Map<String, Object>> LEADING_SIGNAL_RECORDS = signalTypes("record", 8, 11,
        "Alarm", "Beacon", "Chime", "Drum", "Echo", "Flare", "Gong", "Horn", "Icon", "Jingle", "Knock");

    private static final Map<String, Object> WAVE_SIGNAL = signalTypes("record", 74, 11, "Wave").get(0);
    private static final Map<String, Object> XRAY_SIGNAL = signalTypes("interface", 77, 14, "Xray").get(0);
    private static final Map<String, Object> YELL_SIGNAL = signalTypes("enum", 80, 9, "Yell").get(0);
    private static final Map<String, Object> ZONE_SIGNAL = signalTypes("annotation", 84, 15, "Zone").get(0);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("kind=field, maxResults=1: the first field is a constant; 14 fields pass, more remain")
    void fieldKind_firstPage_isTheFirstConstant() throws Exception {
        Seams seams = load("symbol-paging");
        assertPayload(seams, search("log", "field", 0, 1),
            page("log", "field", 0, LOG_FIELDS.subList(0, 1), 14));
    }

    @Test
    @DisplayName("kind=field with the default maxResults returns all 14 fields named log in search order")
    void fieldKind_defaultPage_returnsEveryField() throws Exception {
        Seams seams = load("symbol-paging");
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "log");
        args.put("kind", "field");
        assertPayload(seams, args, page("log", "field", 0, LOG_FIELDS, 14));
    }

    @Test
    @DisplayName("kind=field pages exactly: offset 10 returns the last four fields, three of them, or none past the end")
    void fieldKind_pagesExactly() throws Exception {
        Seams seams = load("symbol-paging");
        assertAll(
            () -> assertPayload(seams, search("log", "field", 10, 5),
                page("log", "field", 10, LOG_FIELDS.subList(10, 14), 14)),
            () -> assertPayload(seams, search("log", "field", 10, 4),
                page("log", "field", 10, LOG_FIELDS.subList(10, 14), 14)),
            () -> assertPayload(seams, search("log", "field", 10, 3),
                page("log", "field", 10, LOG_FIELDS.subList(10, 13), 14)),
            () -> assertPayload(seams, search("log", "field", 5, 5),
                page("log", "field", 5, LOG_FIELDS.subList(5, 10), 14)),
            () -> assertPayload(seams, search("log", "field", 14, 5),
                page("log", "field", 14, List.of(), 14)),
            () -> assertPayload(seams, search("log", "field", 100, 5),
                page("log", "field", 100, List.of(), 14)),
            () -> assertPayload(seams, search("log", "field", 0, 0),
                page("log", "field", 0, List.of(), 14)));
    }

    @Test
    @DisplayName("consecutive pages of kind=field partition the 14 fields in search order")
    void fieldKind_consecutivePages_partitionTheFields() throws Exception {
        Seams seams = load("symbol-paging");
        assertAll(
            () -> assertPayload(seams, search("log", "field", 0, 6),
                page("log", "field", 0, LOG_FIELDS.subList(0, 6), 14)),
            () -> assertPayload(seams, search("log", "field", 6, 6),
                page("log", "field", 6, LOG_FIELDS.subList(6, 12), 14)),
            () -> assertPayload(seams, search("log", "field", 12, 6),
                page("log", "field", 12, LOG_FIELDS.subList(12, 14), 14)));
    }

    @Test
    @DisplayName("kind=method on log returns only the method: no field, parameter or local variable")
    void methodKind_onLog_returnsOnlyTheMethod() throws Exception {
        Seams seams = load("symbol-paging");
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("name", "log");
        method.put("kind", "method");
        method.put("filePath", JOURNAL);
        method.put("line", 12);
        method.put("column", 9);
        method.put("signature", "log(String message)");
        method.put("containingType", PACKAGE + ".Journal");
        assertPayload(seams, search("log", "method", 0, 50), page("log", "method", 0, List.of(method), 1));
    }

    @Test
    @DisplayName("kind=class, maxResults=1, behind eleven records: the first class; 11 classes pass, more remain")
    void classKind_firstPage_behindRecords_isTheFirstClass() throws Exception {
        Seams seams = load("symbol-paging");
        assertPayload(seams, search("*Signal", "class", 0, 1),
            page("*Signal", "class", 0, SIGNAL_CLASSES.subList(0, 1), 11));
    }

    @Test
    @DisplayName("kind=class returns exactly the 11 classes, no record, interface, enum or annotation type")
    void classKind_returnsExactlyTheClasses() throws Exception {
        Seams seams = load("symbol-paging");
        assertAll(
            () -> assertPayload(seams, search("*Signal", "class", 0, 50),
                page("*Signal", "class", 0, SIGNAL_CLASSES, 11)),
            () -> assertPayload(seams, search("*Signal", "class", 10, 5),
                page("*Signal", "class", 10, SIGNAL_CLASSES.subList(10, 11), 11)));
    }

    @Test
    @DisplayName("kind=record, offset 11, behind eleven classes: the twelfth record; 12 records pass, none remain")
    void recordKind_pagePastTheClasses_isTheLastRecord() throws Exception {
        Seams seams = load("symbol-paging");
        List<Map<String, Object>> records = new ArrayList<>(LEADING_SIGNAL_RECORDS);
        records.add(WAVE_SIGNAL);
        assertAll(
            () -> assertPayload(seams, search("*Signal", "record", 11, 1),
                page("*Signal", "record", 11, List.of(WAVE_SIGNAL), 12)),
            () -> assertPayload(seams, search("*Signal", "record", 0, 50),
                page("*Signal", "record", 0, records, 12)));
    }

    @Test
    @DisplayName("interface, enum and annotation kinds each return exactly their one *Signal type")
    void otherTypeKinds_returnTheirOneType() throws Exception {
        Seams seams = load("symbol-paging");
        assertAll(
            () -> assertPayload(seams, search("*Signal", "interface", 0, 50),
                page("*Signal", "interface", 0, List.of(XRAY_SIGNAL), 1)),
            () -> assertPayload(seams, search("*Signal", "enum", 0, 50),
                page("*Signal", "enum", 0, List.of(YELL_SIGNAL), 1)),
            () -> assertPayload(seams, search("*Signal", "annotation", 0, 50),
                page("*Signal", "annotation", 0, List.of(ZONE_SIGNAL), 1)));
    }

    @Test
    @DisplayName("without kind, *Signal returns all 26 types in search order and pages them exactly")
    void noKind_returnsEveryType_andPagesThem() throws Exception {
        Seams seams = load("symbol-paging");
        List<Map<String, Object>> all = new ArrayList<>(LEADING_SIGNAL_RECORDS);
        all.addAll(SIGNAL_CLASSES);
        all.addAll(List.of(WAVE_SIGNAL, XRAY_SIGNAL, YELL_SIGNAL, ZONE_SIGNAL));
        assertAll(
            () -> assertPayload(seams, search("*Signal", null, 0, 50), page("*Signal", null, 0, all, 26)),
            () -> assertPayload(seams, search("*Signal", null, 20, 3),
                page("*Signal", null, 20, all.subList(20, 23), 26)));
    }

    @Test
    @DisplayName("the ? wildcard narrows before paging: two fields pass behind 300 declarations the broadened pattern also matches")
    void questionMarkWildcard_narrowsBeforePaging() throws Exception {
        Path root = helper.copyFixture("symbol-paging");
        StringBuilder wide = new StringBuilder("package org.example.paging;\n\nclass Wide {\n");
        for (int i = 0; i < 300; i++) {
            wide.append(String.format("    int x%03d;\n", i));
        }
        wide.append("    int xa;\n    int xb;\n}\n");
        Files.writeString(root.resolve(WIDE), wide.toString(), StandardCharsets.UTF_8);
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        Seams seams = new Seams(new SearchSymbolsTool(() -> service), new EnvelopeHarness(service));

        // Wide.java: line 0 the package, line 2 the class, lines 3 to 302 the fields x000 to
        // x299, then xa on line 303 and xb on line 304, each name at column 8.
        Map<String, Object> xa = field(WIDE, "xa", "field", 303, 8, "int", "Wide");
        Map<String, Object> xb = field(WIDE, "xb", "field", 304, 8, "int", "Wide");
        assertAll(
            () -> assertPayload(seams, search("x?", "field", 0, 50), page("x?", "field", 0, List.of(xa, xb), 2)),
            () -> assertPayload(seams, search("x?", "field", 0, 1), page("x?", "field", 0, List.of(xa), 2)),
            () -> assertPayload(seams, search("x?", "field", 1, 1), page("x?", "field", 1, List.of(xb), 2)),
            () -> assertPayload(seams, search("x???", "field", 299, 5),
                page("x???", "field", 299, List.of(field(WIDE, "x299", "field", 302, 8, "int", "Wide")), 300)));
    }

    private record Seams(SearchSymbolsTool tool, EnvelopeHarness envelope) {
    }

    private Seams load(String fixture) throws Exception {
        JdtServiceImpl service = helper.loadProject(fixture);
        return new Seams(new SearchSymbolsTool(() -> service), new EnvelopeHarness(service));
    }

    private ObjectNode search(String query, String kind, int offset, int maxResults) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        if (kind != null) {
            args.put("kind", kind);
        }
        args.put("offset", offset);
        args.put("maxResults", maxResults);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(Seams seams, ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct = mapper.valueToTree(seams.tool().execute(args.deepCopy()));
        JsonNode wire = seams.envelope().assertEnvelopeFidelity("search_symbols", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** A page of {@code total} passing declarations starting at {@code offset}. */
    private static Map<String, Object> page(String query, String kind, int offset,
                                            List<Map<String, Object>> results, int total) {
        boolean hasMore = offset + results.size() < total;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        if (kind != null) {
            data.put("kind", kind);
        }
        data.put("results", results);
        data.put("pagination", Map.of("offset", offset, "returned", results.size(), "hasMore", hasMore));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", results.size());
        meta.put("truncated", hasMore);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /** A field result; its containing type, fully qualified, is {@code containerBinaryName} in org.example.paging. */
    private static Map<String, Object> field(String file, String name, String kind, int line, int column,
                                             String type, String containerBinaryName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", kind);
        result.put("filePath", file);
        result.put("line", line);
        result.put("column", column);
        result.put("type", type);
        result.put("containingType", PACKAGE + "." + containerBinaryName);
        return result;
    }

    /** Nested types of Signals named {@code <prefix>Signal}, one every third line from {@code firstLine}. */
    private static List<Map<String, Object>> signalTypes(String kind, int firstLine, int column, String... prefixes) {
        List<Map<String, Object>> types = new ArrayList<>();
        for (int i = 0; i < prefixes.length; i++) {
            String name = prefixes[i] + "Signal";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("kind", kind);
            result.put("filePath", SIGNALS);
            result.put("line", firstLine + 3 * i);
            result.put("column", column);
            result.put("qualifiedName", PACKAGE + ".Signals$" + name);
            result.put("package", PACKAGE);
            types.add(result);
        }
        return List.copyOf(types);
    }
}
