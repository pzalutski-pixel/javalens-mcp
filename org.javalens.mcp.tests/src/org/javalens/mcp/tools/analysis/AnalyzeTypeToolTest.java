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

import java.util.List;
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

    @Test
    @DisplayName("Annotation type's usages block includes the annotationUsages counter")
    @SuppressWarnings("unchecked")
    void annotationType_usagesIncludesAnnotationUsages() {
        // Source line 126-128: when type.isAnnotation(), the usages aggregate also
        // counts annotation references via SearchService.ReferenceKind.ANNOTATION.
        // This branch is only reachable for annotation types — non-annotations skip it.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        assertNotNull(usages, "usages map must be present by default");
        assertTrue(usages.containsKey("annotationUsages"),
            "Annotation type usages must include annotationUsages counter; got keys: "
                + usages.keySet());
    }

    @Test
    @DisplayName("Non-annotation type's usages block does NOT include annotationUsages")
    @SuppressWarnings("unchecked")
    void nonAnnotationType_usagesOmitsAnnotationUsages() {
        // Calculator is a regular class; the isAnnotation() branch is skipped.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        assertNotNull(usages);
        assertFalse(usages.containsKey("annotationUsages"),
            "Non-annotation type must NOT have annotationUsages key; got: " + usages.keySet());
    }

    @Test
    @DisplayName("Enum constant field carries enumConstant=true flag")
    @SuppressWarnings("unchecked")
    void enumConstantField_carriesFlag() {
        // Source line 230-232 sets enumConstant=true only for fields where
        // field.isEnumConstant() returns true. Color enum has RED/GREEN/BLUE constants.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.TypeKindsFixture.Color");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> members = (Map<String, Object>) getData(r).get("members");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) members.get("fields");
        Map<String, Object> red = fields.stream()
            .filter(f -> "RED".equals(f.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("RED enum constant must be in fields; got: " + fields));
        assertEquals(Boolean.TRUE, red.get("enumConstant"),
            "RED must carry enumConstant=true; got: " + red);
    }

    @Test
    @DisplayName("Regular field omits enumConstant flag (only set when true)")
    @SuppressWarnings("unchecked")
    void regularField_omitsEnumConstantFlag() {
        // Calculator.lastResult is a regular int field. Source only emits the flag when
        // isEnumConstant() returns true; non-enum fields must omit it.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> members = (Map<String, Object>) getData(r).get("members");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) members.get("fields");
        Map<String, Object> lastResult = fields.stream()
            .filter(f -> "lastResult".equals(f.get("name")))
            .findFirst()
            .orElseThrow();
        assertFalse(lastResult.containsKey("enumConstant"),
            "Regular field must omit enumConstant; got: " + lastResult);
    }

    @Test
    @DisplayName("TypeKindsFixture nested types: each is classified by kind (enum/class/interface)")
    @SuppressWarnings("unchecked")
    void nestedTypes_kindsReportedPerType() {
        // TypeKindsFixture has nested types of different kinds — at minimum:
        //   Color (enum), GenericContainer (class), Inner (class),
        //   DefaultMethodHolder (interface), BoundedBox (class).
        // The members.nestedTypes list must classify each correctly via TypeKindResolver
        // so a regression in TypeKindResolver or the per-kind branches would surface here.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.TypeKindsFixture");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> members = (Map<String, Object>) getData(r).get("members");
        List<Map<String, Object>> nested = (List<Map<String, Object>>) members.get("nestedTypes");
        assertNotNull(nested);
        Map<String, String> kindByName = new java.util.HashMap<>();
        for (Map<String, Object> n : nested) {
            kindByName.put((String) n.get("name"), (String) n.get("kind"));
        }
        assertEquals("enum", kindByName.get("Color"),
            "Color must be classified as enum; got: " + kindByName);
        assertEquals("class", kindByName.get("GenericContainer"),
            "GenericContainer must be classified as class; got: " + kindByName);
        assertEquals("class", kindByName.get("Inner"),
            "Inner must be classified as class; got: " + kindByName);
        assertEquals("interface", kindByName.get("DefaultMethodHolder"),
            "DefaultMethodHolder must be classified as interface; got: " + kindByName);
        assertEquals("class", kindByName.get("BoundedBox"),
            "BoundedBox must be classified as class; got: " + kindByName);
    }

    @Test
    @DisplayName("Generic class with bounded type parameters: typeParameters and bounds appear in type info")
    @SuppressWarnings("unchecked")
    void analyzeType_genericClassWithBoundedTypeParameters_reportsBoundedParams() {
        // BoundedMultiParam<T extends Number, U extends Comparable<U>> exercises:
        //   - multi-parameter list (two distinct names)
        //   - simple upper bound on T (Number)
        //   - F-bounded self-referential bound on U (Comparable<U>)
        // The type info MUST surface both names and their bounds; without this
        // a consumer cannot reproduce the declaration faithfully.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.BoundedMultiParam");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> type = (Map<String, Object>) getData(r).get("type");
        assertNotNull(type);

        List<String> typeParameters = (List<String>) type.get("typeParameters");
        assertNotNull(typeParameters, "type.typeParameters must be present for a generic class; got: " + type);
        assertEquals(List.of("T", "U"), typeParameters,
            "typeParameters must list the declared parameters in order; got: " + typeParameters);

        Map<String, Object> bounds = (Map<String, Object>) type.get("typeParameterBounds");
        assertNotNull(bounds,
            "type.typeParameterBounds must report bounds for each parameter; got: " + type);
        List<String> tBounds = (List<String>) bounds.get("T");
        List<String> uBounds = (List<String>) bounds.get("U");
        assertNotNull(tBounds, "Bounds for T missing; got: " + bounds);
        assertNotNull(uBounds, "Bounds for U missing; got: " + bounds);
        assertTrue(tBounds.contains("Number"),
            "T bound must include Number; got: " + tBounds);
        assertTrue(uBounds.stream().anyMatch(b -> b.contains("Comparable")),
            "U bound must reference Comparable; got: " + uBounds);
    }
}
