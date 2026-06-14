package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindUnusedCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindUnusedCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindUnusedCodeTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindUnusedCodeTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("UnusedCode.java: exact unused counts 2 fields + 2 methods = 4")
    void findsUnusedCodeComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(2, ((Number) data.get("unusedFieldCount")).intValue());
        assertEquals(2, ((Number) data.get("unusedMethodCount")).intValue());
        assertEquals(4, ((Number) data.get("totalUnused")).intValue());
        assertEquals(4, ((List<?>) data.get("unusedItems")).size());
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        ObjectNode noFields = objectMapper.createObjectNode();
        noFields.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noFields.put("includeFields", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) getData(tool.execute(noFields)).get("unusedItems");
        assertFalse(items1.stream().anyMatch(i -> "field".equals(i.get("kind"))));

        ObjectNode noMethods = objectMapper.createObjectNode();
        noMethods.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noMethods.put("includeMethods", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) getData(tool.execute(noMethods)).get("unusedItems");
        assertFalse(items2.stream().anyMatch(i -> "method".equals(i.get("kind"))));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalUnused"));
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    @Test
    @DisplayName("UnusedCode.java: exactly 4 unused private members with exact kind and 0-based name position")
    void unusedCode_detectsExactUnusedMembers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) getData(r).get("unusedItems");

        // Exactly the four declared-unused private members — the exact-set equality
        // is the isolation oracle: used (usedField/usedPrivateMethod) and non-private
        // (public/protected/package-private) members are all excluded.
        assertEquals(4, items.size(), "exactly 4 unused members; got: " + items);

        Map<String, List<Object>> byName = new java.util.HashMap<>();
        for (Map<String, Object> i : items) {
            byName.put((String) i.get("name"), List.of(
                i.get("kind"),
                ((Number) i.get("line")).intValue(),
                ((Number) i.get("column")).intValue()));
        }
        // name -> [kind, 0-based name line, 0-based name column] (tool reports the NAME position)
        assertEquals(Map.of(
            "unusedField", List.of("field", 8, 16),
            "unusedStringField", List.of("field", 11, 19),
            "unusedPrivateMethod", List.of("method", 22, 17),
            "unusedPrivateMethodWithReturn", List.of("method", 29, 16)),
            byName);
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("unusedItems");
    }

    @Test
    @DisplayName("Each unused item carries name, kind, filePath, line, column — with valid values")
    void unusedEntry_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> items = itemsOf(r);
        assertFalse(items.isEmpty(), "UnusedCode.java has known unused members; itemsOf must not be empty");
        for (Map<String, Object> i : items) {
            // name: non-blank string
            String name = (String) i.get("name");
            assertNotNull(name, "name missing: " + i);
            assertFalse(name.isBlank(), "name must be non-blank; got: " + i);
            // kind: one of the documented values
            String kind = (String) i.get("kind");
            assertNotNull(kind, "kind missing: " + i);
            assertTrue(List.of("field", "method", "class").contains(kind),
                "kind must be field/method/class (lowercase); got: " + i);
            // filePath: ends with .java
            String filePath = (String) i.get("filePath");
            assertNotNull(filePath, "filePath missing: " + i);
            assertTrue(filePath.endsWith(".java"),
                "filePath must point to a .java file; got: " + i);
            assertTrue(filePath.endsWith("UnusedCode.java"),
                "all items in this scoped query must come from UnusedCode.java; got: " + i);
            // line: non-negative integer
            Number line = (Number) i.get("line");
            assertNotNull(line, "line missing: " + i);
            assertTrue(line.intValue() >= 0, "line must be >= 0; got: " + i);
            // column: non-negative integer
            Number column = (Number) i.get("column");
            assertNotNull(column, "column missing: " + i);
            assertTrue(column.intValue() >= 0, "column must be >= 0; got: " + i);
        }
    }

    @Test
    @DisplayName("Field entries carry type; method entries carry signature")
    void unusedEntry_kindSpecificFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> i : itemsOf(r)) {
            if ("field".equals(i.get("kind"))) {
                assertNotNull(i.get("type"), "Field entry must carry `type`: " + i);
            } else if ("method".equals(i.get("kind"))) {
                assertNotNull(i.get("signature"), "Method entry must carry `signature`: " + i);
            }
        }
    }

    @Test
    @DisplayName("unusedFieldCount + unusedMethodCount == totalUnused")
    void counts_consistent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        long total = ((Number) data.get("totalUnused")).longValue();
        long fields = ((Number) data.get("unusedFieldCount")).longValue();
        long methods = ((Number) data.get("unusedMethodCount")).longValue();
        assertEquals(total, fields + methods,
            "totalUnused must equal unusedFieldCount + unusedMethodCount; got total=" + total
                + " fields=" + fields + " methods=" + methods);
    }

    @Test
    @DisplayName("Private method referenced only via method reference (this::format) is NOT reported as unused")
    void privateMethodReferencedOnlyViaMethodReference_isNotUnused() {
        // MethodRefOnlyConsumer has a private `format(int)` method that is used ONLY
        // via `this::format` in `formatAll(...).stream().map(this::format)`. There is
        // no direct invocation `format(n)` anywhere. The usage-detection visitor must
        // recognize ExpressionMethodReference (and TypeMethodReference, SuperMethodReference)
        // as usages, or this private method gets flagged as unused.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/MethodRefOnlyConsumer.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> items = itemsOf(r);
        boolean formatFlagged = items.stream()
            .anyMatch(i -> "format".equals(i.get("name")) && "method".equals(i.get("kind")));
        assertFalse(formatFlagged,
            "format() is used via this::format method reference — must NOT be reported " +
                "as unused; got items: " + items);
    }

    @Test
    @DisplayName("includeFields=false and includeMethods=false → totalUnused=0")
    void bothFlagsFalse_returnsEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");
        args.put("includeFields", false);
        args.put("includeMethods", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(0, ((Number) getData(r).get("totalUnused")).intValue());
    }

    @Test
    @DisplayName("Private fields read in generic and non-generic declaring classes are NOT flagged unused")
    @SuppressWarnings("unchecked")
    void privateFieldsRead_inGenericClass_areNotReportedUnused() {
        // The genericunused/ fixture establishes a 2x2 matrix:
        //   PlainClass (concrete, non-generic) — control
        //   AbstractPlainClass (abstract, non-generic) — control
        //   GenericClass<T> (concrete, generic) — issue #17 surface
        //   AbstractGenericClass<T> (abstract, generic) — issue #17 surface
        // Every file's private field IS read in a method body. Tool must
        // report zero unused fields for every file.
        for (String name : List.of(
                "PlainClass", "AbstractPlainClass",
                "GenericClass", "AbstractGenericClass")) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("filePath", "src/main/java/com/example/genericunused/" + name + ".java");
            ToolResponse r = tool.execute(args);
            assertTrue(r.isSuccess(), name + " analysis must succeed");
            List<Map<String, Object>> items = (List<Map<String, Object>>) getData(r).get("unusedItems");
            assertNotNull(items, name + ": unusedItems missing");
            java.util.List<String> fieldNames = items.stream()
                .filter(i -> "field".equals(i.get("kind")))
                .map(i -> (String) i.get("name"))
                .toList();
            assertTrue(fieldNames.isEmpty(),
                name + ": private field is read in a method body and must not be flagged unused; got: " + items);
        }
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: UnusedCode reports the 4 unused private members, not the used ones")
    void envelope_unusedCode_exactMembers() {
        ObjectNode args = envelope.args();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");
        JsonNode payload = envelope.assertEnvelopeFidelity("find_unused_code", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_unused_code failed through the envelope: " + payload);
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (JsonNode i : payload.get("data").get("unusedItems")) names.add(i.get("name").asText());
        assertEquals(java.util.Set.of("unusedField", "unusedStringField",
                "unusedPrivateMethod", "unusedPrivateMethodWithReturn"), names,
            "exactly the four unused private members must survive the envelope; got: " + names);
    }
}
