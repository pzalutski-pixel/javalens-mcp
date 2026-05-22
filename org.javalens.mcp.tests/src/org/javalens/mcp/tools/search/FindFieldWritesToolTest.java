package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindFieldWritesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindFieldWritesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindFieldWritesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds field writes with complete response")
    void findsFieldWritesComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);  // userName field
        args.put("column", 19);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("userName", data.get("field"));
        assertNotNull(data.get("declaringType"));
        assertNotNull(data.get("totalWriteLocations"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertNotNull(writes);
        if (!writes.isEmpty()) {
            Map<String, Object> write = writes.get(0);
            assertNotNull(write.get("line"));
            assertEquals("WRITE", write.get("accessType"));
        }
    }

    @Test @DisplayName("supports maxResults parameter")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);
        args.put("column", 19);
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> writes = (List<?>) getData(r).get("writeLocations");
        assertTrue(writes.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 13);
        noFile.put("column", 19);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", refactoringTargetPath);
        noLine.put("column", 19);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-field position gracefully")
    void handlesNonFieldPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 20);  // Method, not field
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("FieldHolder.pet writes: 2 in own constructors + swap() + extract()")
    void fieldHolderPet_writesExactCountAcrossFiles() {
        String fieldHolderPath = projectPath.resolve("src/main/java/com/example/FieldHolder.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fieldHolderPath);
        args.put("line", 4);  // 0-based: `    Animal pet;`
        args.put("column", 11);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("pet", data.get("field"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertNotNull(writes);
        // 4 explicit writes: FieldHolder() default ctor `this.pet = new Animal()`, FieldHolder(Animal)
        // ctor `this.pet = pet`, WidgetHelper.swap `holder.pet = newPet`, WidgetHelper.extract
        // `holder.pet = null`. Some implementations may also count the declaration as a write;
        // assert at least the 4 explicit assignment writes are present.
        assertTrue(writes.size() >= 4,
            "Expected at least 4 writes to FieldHolder.pet; got " + writes.size() + " writes: " + writes);
        for (Map<String, Object> w : writes) {
            assertEquals("WRITE", w.get("accessType"));
        }
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> writesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("writeLocations");
    }

    @Test
    @DisplayName("requires column parameter")
    void requiresColumnParam() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);
        assertFalse(tool.execute(args).isSuccess(),
            "Missing column must yield error");
    }

    @Test
    @DisplayName("Calculator.lastResult has exactly 3 writes (one per arithmetic method)")
    void calculatorLastResult_exactlyThreeWrites() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);   // 0-based: `    private int lastResult;`
        args.put("column", 16); // on `lastResult` identifier
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("lastResult", data.get("field"));
        assertEquals("Calculator", data.get("declaringType"));

        List<Map<String, Object>> writes = writesOf(r);
        assertEquals(3, writes.size(),
            "Calculator.lastResult is assigned in add(), subtract(), multiply() — exactly 3 writes; got: " + writes);
        for (Map<String, Object> w : writes) {
            assertEquals("WRITE", w.get("accessType"), "Every entry must report accessType=WRITE: " + w);
        }
    }

    @Test
    @DisplayName("Reads of lastResult (return statements, getLastResult) are NOT reported as writes")
    void calculatorLastResult_excludesReads() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Writes are at 0-based lines 15, 26, 37 (1-based 16, 27, 38). The pure reads
        // are at 0-based lines 16, 27, 38, 46 (`return lastResult;` and `return lastResult;`
        // in getLastResult). None of those read lines may appear in writes.
        java.util.Set<Integer> readOnlyLines = java.util.Set.of(16, 27, 38, 46);
        for (Map<String, Object> w : writesOf(r)) {
            int line = ((Number) w.get("line")).intValue();
            assertFalse(readOnlyLines.contains(line),
                "Read-only line " + line + " must not be reported as a write; got: " + w);
        }
    }

    @Test
    @DisplayName("Per-write entry includes filePath, line, column, context, accessType")
    void writeEntries_includeFullLocation() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> writes = writesOf(r);
        assertFalse(writes.isEmpty());
        for (Map<String, Object> w : writes) {
            String fp = (String) w.get("filePath");
            assertNotNull(fp, "filePath missing: " + w);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + w);
            assertTrue(((Number) w.get("line")).intValue() >= 0, "line >= 0; got: " + w);
            assertTrue(((Number) w.get("column")).intValue() >= 0, "column >= 0; got: " + w);
            String ctx = (String) w.get("context");
            assertNotNull(ctx, "context missing: " + w);
            assertFalse(ctx.isBlank(), "context non-blank; got: " + w);
            assertEquals("WRITE", w.get("accessType"));
        }
    }

    @Test
    @DisplayName("fieldType signature is reported")
    void fieldTypeSignature_present() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // Calculator.lastResult is `int` — JDT type signature is "I".
        assertEquals("I", data.get("fieldType"),
            "Expected JDT signature 'I' for int field; got: " + data.get("fieldType"));
    }

    @Test
    @DisplayName("FieldHolder.pet writes come from BOTH FieldHolder.java and WidgetHelper.java (cross-file)")
    void fieldHolderPet_crossFileFileSet() {
        String fieldHolderPath = projectPath.resolve("src/main/java/com/example/FieldHolder.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fieldHolderPath);
        args.put("line", 4);
        args.put("column", 11);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> files = new java.util.HashSet<>();
        for (Map<String, Object> w : writesOf(r)) {
            String fp = ((String) w.get("filePath")).replace('\\', '/');
            if (fp.endsWith("FieldHolder.java")) files.add("FieldHolder.java");
            else if (fp.endsWith("WidgetHelper.java")) files.add("WidgetHelper.java");
        }
        assertEquals(java.util.Set.of("FieldHolder.java", "WidgetHelper.java"), files,
            "Pet writes must span FieldHolder.java and WidgetHelper.java; got: " + files);
    }

    @Test
    @DisplayName("Position on a field REFERENCE (not declaration) finds the same writes")
    void positionOnFieldReference_findsWrites() {
        // Position cursor on `pet` in `holder.pet = newPet;` (WidgetHelper.swap).
        String widgetPath = projectPath.resolve("src/main/java/com/example/WidgetHelper.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", widgetPath);
        args.put("line", 9);    // 0-based: `        holder.pet = newPet;`
        args.put("column", 15); // on `pet` after `holder.`
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on field reference must resolve to the field; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        assertEquals("pet", getData(r).get("field"));
        // Same 4 writes as positioning on the declaration.
        assertTrue(writesOf(r).size() >= 4,
            "Same field writes expected when positioning on a reference; got: " + writesOf(r));
    }

    @Test
    @DisplayName("Local variable position → invalidParameter with kind=Variable")
    void positionOnLocalVariable_returnsErrorWithKind() {
        // WidgetHelper.extract line 13 (0-based): `        Animal current = holder.pet;`
        String widgetPath = projectPath.resolve("src/main/java/com/example/WidgetHelper.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", widgetPath);
        args.put("line", 13);
        args.put("column", 15); // on `current`

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "Local variable position must error");
        // Error message should mention non-field. Don't be over-strict on wording — just
        // verify the error explicitly mentions the kind reported by getElementKind.
        String msg = r.getError() != null ? r.getError().getMessage() : "";
        assertTrue(msg.toLowerCase().contains("not a field") || msg.contains("Variable"),
            "Error must explain non-field kind; got: " + msg);
    }

    @Test
    @DisplayName("maxResults exactly caps results and meta.truncated=true")
    void maxResults_capsExactly() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> writes = writesOf(r);
        // 3 total writes, capped to 2.
        assertEquals(2, writes.size(), "maxResults=2 must cap to 2 writes; got: " + writes.size());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated(),
            "meta.truncated must be true when writes are capped below the true count");
    }

    @Test
    @DisplayName("Generic-class field write: find_field_writes surfaces this.value = v inside set()")
    @SuppressWarnings("unchecked")
    void genericClassField_writeDetected() {
        // GenericClass<T> has `private T value;` and a `set(T v)` method
        // assigning `this.value = v;`. The field's only write site is that
        // assignment. find_field_writes positioned on the field declaration
        // must surface it. SearchEngine's WRITE_ACCESSES filter is
        // index-driven and unaffected by the IBinding equality quirk.
        String generic = projectPath.resolve(
            "src/main/java/com/example/genericunused/GenericClass.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", 9);
        args.put("column", 14);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "find_field_writes on a generic-class field must succeed; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("value", data.get("field"));
        assertEquals("GenericClass", data.get("declaringType"));

        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertNotNull(writes);
        assertEquals(1, writes.size(),
            "Exactly one write: `this.value = v;` inside set(); got: " + writes);
    }

    @Test
    @DisplayName("totalWriteLocations == writeLocations.size()")
    void totalWriteLocations_equalsListSize() {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalWriteLocations")).intValue();
        assertEquals(total, writesOf(r).size());
    }
}
