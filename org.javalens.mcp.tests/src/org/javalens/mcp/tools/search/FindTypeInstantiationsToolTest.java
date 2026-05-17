package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTypeInstantiationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTypeInstantiationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTypeInstantiationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getInstantiations(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds instantiations of project type")
    void findsInstantiationsOfProjectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getInstantiations(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
        assertEquals("com.example.Calculator", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        assertTrue(getInstantiations(getData(tool.execute(args))).size() <= 1);
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
    @DisplayName("ConstructorTarget instantiations from ConstructorCaller: 5 explicit `new` sites")
    void constructorTarget_findsAllNewSites() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.ConstructorTarget");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // ConstructorCaller has: makeOne -> new ConstructorTarget("alpha", 1) (1),
        // makeOneArg -> new ConstructorTarget("beta") (1),
        // makeMany -> 3 new ConstructorTarget calls. Total = 5.
        // Plus ConstructorTarget itself does `this(name, 0)` which is a constructor
        // delegation, NOT an instantiation; should NOT be counted.
        assertEquals(5, ((Number) getData(r).get("totalCount")).intValue(),
            "Expected exactly 5 `new ConstructorTarget(...)` instantiations; got: "
                + getData(r).get("totalCount") + " (" + getInstantiations(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> instOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("Instantiation entry includes filePath, line, column, offset, length")
    void instantiationEntry_includesFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.ConstructorTarget");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> insts = instOf(r);
        assertFalse(insts.isEmpty());
        for (Map<String, Object> i : insts) {
            assertNotNull(i.get("filePath"), "filePath missing: " + i);
            assertNotNull(i.get("line"), "line missing: " + i);
            assertNotNull(i.get("column"), "column missing: " + i);
            assertNotNull(i.get("offset"), "offset missing: " + i);
            assertNotNull(i.get("length"), "length missing: " + i);
        }
    }

    @Test
    @DisplayName("Calculator instantiations: exactly 5 across SearchPatterns + UserService + SampleTest")
    void calculator_exactCountCrossFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> insts = instOf(r);
        // SearchPatterns.java has 3 `new Calculator()` (lines 58, 212, 227),
        // UserService.java has 1 (line 20), SampleTest.java has 1 (line 22). Total = 5.
        assertEquals(5, insts.size(),
            "Calculator must be instantiated exactly 5 times; got: " + insts);

        java.util.Set<String> files = new java.util.HashSet<>();
        for (Map<String, Object> i : insts) {
            String fp = ((String) i.get("filePath")).replace('\\', '/');
            files.add(fp.substring(fp.lastIndexOf('/') + 1));
        }
        assertEquals(
            java.util.Set.of("SearchPatterns.java", "UserService.java", "SampleTest.java"),
            files,
            "Calculator instantiations must span exactly 3 files; got: " + files);
    }

    @Test
    @DisplayName("CLASS_INSTANCE_CREATION_TYPE_REFERENCE distinction: refs/casts/instanceof NOT counted")
    void instantiationDistinction_excludesOtherRefs() {
        // Calculator has many type references (List<Calculator>, instanceof, casts, declarations).
        // Only `new Calculator()` sites count. We already verify count = 5 above; here also
        // assert line numbers correspond to known `new` lines:
        //   SearchPatterns.java 0-based 57, 211, 226
        //   UserService.java 0-based 19
        //   SampleTest.java 0-based 21
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Build (file, line) tuple set.
        java.util.Set<String> tuples = new java.util.HashSet<>();
        for (Map<String, Object> i : instOf(r)) {
            String fp = ((String) i.get("filePath")).replace('\\', '/');
            String fileName = fp.substring(fp.lastIndexOf('/') + 1);
            int line = ((Number) i.get("line")).intValue();
            tuples.add(fileName + ":" + line);
        }
        assertEquals(
            java.util.Set.of(
                "SearchPatterns.java:57",
                "SearchPatterns.java:211",
                "SearchPatterns.java:226",
                "UserService.java:19",
                "SampleTest.java:21"),
            tuples,
            "Instantiation locations must match `new Calculator()` lines; got: " + tuples);
    }

    @Test
    @DisplayName("Animal instantiations: exactly 1 in FieldHolder default constructor")
    void animal_singleInstantiation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> insts = instOf(r);
        // FieldHolder.java line 8 (1-based): `this.pet = new Animal();`
        assertEquals(1, insts.size(),
            "Animal must be instantiated exactly once in fixtures; got: " + insts);
        Map<String, Object> only = insts.get(0);
        String fp = ((String) only.get("filePath")).replace('\\', '/');
        assertTrue(fp.endsWith("FieldHolder.java"),
            "Animal instantiation must be in FieldHolder.java; got: " + fp);
    }

    @Test
    @DisplayName("Uninstantiated type returns empty list (isolation)")
    void uninstantiatedType_returnsEmpty() {
        // Marker is an annotation interface — `new Marker()` is not a legal expression,
        // so it appears in zero CLASS_INSTANCE_CREATION_TYPE_REFERENCE matches.
        // (Note: IShape has `new IShape() { }` anonymous-class instantiation in
        // AnonymousShapeUser, which JDT DOES count as an instantiation — so it is not
        // a valid "never-instantiated" target.)
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Marker annotation must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        assertEquals(0, instOf(r).size(),
            "@Marker annotation type is never instantiated; got: " + instOf(r));
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, instOf(r).size(),
            "maxResults=2 must cap instantiations to exactly 2");
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
    @DisplayName("totalCount == locations.size()")
    void totalCount_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, instOf(r).size());
    }
}
