package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeTypeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;
    private AnalyzeTypeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeTypeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("Calculator: exact member counts, no implicit superclass/interfaces in hierarchy, instantiated in fixtures")
    @SuppressWarnings("unchecked")
    void calculator_exactMembersHierarchyAndUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Type info — exact identity
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("Calculator", type.get("name"));
        assertEquals("com.example.Calculator", type.get("qualifiedName"));
        assertEquals("com.example", type.get("package"));
        assertEquals("class", type.get("kind"));

        // Members — exact counts. Calculator declares: no constructors (default), 4
        // methods (add, subtract, multiply, getLastResult), 1 field (lastResult), no
        // nested types.
        Map<String, Object> members = (Map<String, Object>) data.get("members");
        assertEquals(0, ((Number) members.get("constructorCount")).intValue(),
            "Calculator declares no explicit constructors; got: " + members.get("constructors"));
        assertEquals(4, ((Number) members.get("methodCount")).intValue(),
            "Calculator declares exactly 4 methods (add/subtract/multiply/getLastResult); got: "
                + members.get("methods"));
        assertEquals(1, ((Number) members.get("fieldCount")).intValue(),
            "Calculator declares exactly 1 field (lastResult); got: " + members.get("fields"));
        assertEquals(0, ((Number) members.get("nestedTypeCount")).intValue(),
            "Calculator declares no nested types; got: " + members.get("nestedTypes"));

        // Hierarchy — Calculator's superclass is Object, which the tool filters out;
        // Calculator implements no interfaces. The hierarchy map must NOT carry
        // superclass or interfaces keys (the tool omits empties).
        Map<String, Object> hierarchy = (Map<String, Object>) data.get("hierarchy");
        assertNotNull(hierarchy);
        assertFalse(hierarchy.containsKey("superclass"),
            "Object is filtered as a non-meaningful superclass; key must be absent; got: " + hierarchy);
        assertFalse(hierarchy.containsKey("interfaces"),
            "Calculator implements no interfaces; key must be absent; got: " + hierarchy);

        // Usages — Calculator is instantiated in fixtures (SearchPatterns.createObjects,
        // SearchPatterns.InnerClass.createCalculator, and others). Cross-tool intent:
        // analyze_type's usage summary must be consistent with the find_* tools, so
        // instantiations must be > 0.
        Map<String, Object> usages = (Map<String, Object>) data.get("usages");
        assertNotNull(usages);
        int instantiations = ((Number) usages.get("instantiations")).intValue();
        assertTrue(instantiations > 0,
            "Calculator is instantiated in SearchPatterns and other fixtures; instantiations must be > 0; got: "
                + usages);
        int total = ((Number) usages.get("total")).intValue();
        assertTrue(total >= instantiations,
            "usages.total must include instantiations; got: " + usages);
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) getData(r).get("type");
        assertEquals("Calculator", type.get("name"));
    }

    @Test @DisplayName("controls usages output")
    void controlsUsagesOutput() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("includeUsages", false);

        assertNull(getData(tool.execute(args)).get("usages"));
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        assertFalse(tool.execute(unknown).isSuccess());

        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("typeName", "");
        assertFalse(tool.execute(empty).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Top-level response carries type, members, hierarchy, usages")
    void responseShape_compoundedSections() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : java.util.List.of("type", "members", "hierarchy", "usages")) {
            assertNotNull(data.get(key), key + " missing on analyze_type response: " + data.keySet());
        }
    }

    @Test
    @DisplayName("FilledCircle has interface dependency in hierarchy.interfaces")
    @SuppressWarnings("unchecked")
    void filledCircle_hierarchyInterfaces() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.FilledCircle");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> hierarchy = (Map<String, Object>) getData(r).get("hierarchy");
        // FilledCircle implements IFillable (which extends IShape).
        assertNotNull(hierarchy.get("interfaces"),
            "FilledCircle must have an `interfaces` entry in hierarchy; got: " + hierarchy);
    }

    @Test
    @DisplayName("Annotation type is reported with kind='annotation'")
    @SuppressWarnings("unchecked")
    void annotationType_kindReported() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> type = (Map<String, Object>) getData(r).get("type");
        assertEquals("annotation", type.get("kind"),
            "Marker is an annotation type — kind must be 'annotation'; got: " + type);
    }

    @Test
    @DisplayName("includeUsages=false omits usages section entirely")
    void includeUsagesFalse_omitsUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("includeUsages", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertNull(getData(r).get("usages"));
    }

    @Test
    @DisplayName("Members section has constructorCount + methodCount + fieldCount + nestedTypeCount")
    void membersSection_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> members = (Map<String, Object>) getData(r).get("members");
        for (String key : java.util.List.of("constructorCount", "methodCount", "fieldCount", "nestedTypeCount")) {
            assertNotNull(members.get(key), key + " missing on members: " + members);
        }
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator: analyze_type member counts agree with get_type_members (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculator_memberCountsAgreeWithGetTypeMembers() throws Exception {
        // Aggregate vs detail: if analyze_type's counts disagree with get_type_members,
        // one of the tools is wrong. This test prevents the class of bugs where the
        // compounding analyzer drifts from the underlying source-of-truth.
        GetTypeMembersTool detail = new GetTypeMembersTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        Map<String, Object> aggregateData = getData(tool.execute(args));
        Map<String, Object> detailData = getData(detail.execute(args));

        Map<String, Object> members = (Map<String, Object>) aggregateData.get("members");
        int aggregateConstructors = ((Number) members.get("constructorCount")).intValue();
        int aggregateMethods = ((Number) members.get("methodCount")).intValue();
        int aggregateFields = ((Number) members.get("fieldCount")).intValue();
        int aggregateNested = ((Number) members.get("nestedTypeCount")).intValue();

        // get_type_members returns a single `methods` list that includes constructors
        // (IType.getMethods() surfaces both); the aggregate splits them. Sum to compare.
        java.util.List<?> detailMethods = (java.util.List<?>) detailData.get("methods");
        java.util.List<?> detailFields = (java.util.List<?>) detailData.get("fields");
        java.util.List<?> detailNested = (java.util.List<?>) detailData.get("nestedTypes");

        assertEquals(aggregateConstructors + aggregateMethods, detailMethods.size(),
            "analyze_type constructorCount+methodCount must equal get_type_members methods list size; "
                + "aggregate ctors=" + aggregateConstructors + " methods=" + aggregateMethods
                + " vs detail methods=" + detailMethods.size());
        assertEquals(aggregateFields, detailFields.size(),
            "field counts must agree; aggregate=" + aggregateFields + " detail=" + detailFields.size());
        assertEquals(aggregateNested, detailNested.size(),
            "nested-type counts must agree; aggregate=" + aggregateNested + " detail=" + detailNested.size());
    }
}
