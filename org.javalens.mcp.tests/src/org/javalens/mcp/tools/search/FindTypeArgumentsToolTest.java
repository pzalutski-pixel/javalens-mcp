package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTypeArgumentsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTypeArgumentsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTypeArgumentsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getUsages(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds type argument usages")
    void findsTypeArgumentUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.String");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getUsages(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.String");
        args.put("maxResults", 1);
        assertTrue(getUsages(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator as generic type argument: at least 1 (SearchPatterns has List<Calculator> field + use)")
    void calculator_findsTypeArgumentUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns declares `private List<Calculator> calculatorList;` and inside
        // useGenerics has `List<Calculator> calcs = new ArrayList<>();`. JDT may report 1
        // or 2 depending on how `new ArrayList<>()`'s diamond inference is counted.
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertTrue(total >= 1,
            "Expected at least 1 List<Calculator> usage; got: "
                + total + " (" + getUsages(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> usagesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("Calculator as type argument: exactly 2 occurrences (field + local)")
    void calculator_exactlyTwoTypeArgumentUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> usages = usagesOf(r);
        // SearchPatterns.java:27 `private List<Calculator> calculatorList;`
        // SearchPatterns.java:211 `List<Calculator> calcs = new ArrayList<>();`
        // Exactly 2 type-argument occurrences of Calculator.
        assertEquals(2, usages.size(),
            "Calculator must appear in exactly 2 type-argument positions; got: " + usages);
    }

    @Test
    @DisplayName("All Calculator type-argument usages are in SearchPatterns.java (cross-file isolation)")
    void calculator_typeArgUsages_allInSearchPatterns() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> u : usagesOf(r)) {
            String fp = ((String) u.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith("SearchPatterns.java"),
                "Calculator type-arg usages must come from SearchPatterns.java; got: " + fp);
        }
    }

    @Test
    @DisplayName("ConstructorTarget as type argument: exactly 2 in ConstructorCaller.java")
    void constructorTarget_exactlyTwoUsagesInCaller() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.ConstructorTarget");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> usages = usagesOf(r);
        // ConstructorCaller.java:16  public List<ConstructorTarget> makeMany() {
        // ConstructorCaller.java:17  List<ConstructorTarget> all = new ArrayList<>();
        assertEquals(2, usages.size(),
            "ConstructorTarget must appear in exactly 2 type-argument positions; got: " + usages);
        for (Map<String, Object> u : usages) {
            String fp = ((String) u.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith("ConstructorCaller.java"),
                "ConstructorTarget type-arg usages must come from ConstructorCaller.java; got: " + fp);
        }
    }

    @Test
    @DisplayName("Per-usage entry includes filePath, line, column, offset, length, context")
    void usageEntries_includeFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> usages = usagesOf(r);
        assertFalse(usages.isEmpty());
        for (Map<String, Object> u : usages) {
            String fp = (String) u.get("filePath");
            assertNotNull(fp, "filePath missing: " + u);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + u);
            assertTrue(((Number) u.get("line")).intValue() >= 0, "line >= 0; got: " + u);
            assertTrue(((Number) u.get("column")).intValue() >= 0, "column >= 0; got: " + u);
            assertTrue(((Number) u.get("offset")).intValue() >= 0, "offset >= 0; got: " + u);
            assertTrue(((Number) u.get("length")).intValue() > 0, "length > 0; got: " + u);
            String ctx = (String) u.get("context");
            assertNotNull(ctx, "context missing: " + u);
            assertFalse(ctx.isBlank(), "context non-blank; got: " + u);
        }
    }

    @Test
    @DisplayName("TYPE_ARGUMENT_TYPE_REFERENCE distinction: plain field-type, instantiation, cast NOT counted")
    void typeArgDistinction_excludesNonTypeArgUsages() {
        // Animal is used:
        //  - as field type: `Animal pet;` (FieldHolder.java line 5)
        //  - as constructor instantiation: `this.pet = new Animal();` (FieldHolder.java line 8)
        //  - as parameter type: WidgetHelper.swap, WidgetHelper.extract (return)
        // It is NEVER used as a type argument (no List<Animal>, Map<*, Animal>, etc).
        // Expect exactly 0 type-argument usages.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Animal type should resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        assertEquals(0, usagesOf(r).size(),
            "Animal is never used as a generic type argument; got: " + usagesOf(r));
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, usagesOf(r).size(),
            "maxResults=1 must cap usages to 1");
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
    void maxResults_large_noTruncation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.FALSE, meta.getTruncated());
    }

    @Test
    @DisplayName("totalUsages == typeArgumentUsages.size()")
    void totalUsages_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, usagesOf(r).size());
    }
}
