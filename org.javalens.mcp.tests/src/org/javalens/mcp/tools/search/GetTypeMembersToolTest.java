package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetTypeMembersToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetTypeMembersTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeMembersTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("gets type members with complete response")
    void getsTypeMembersComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify type info
        @SuppressWarnings("unchecked")
        Map<String, Object> typeInfo = (Map<String, Object>) data.get("type");
        assertEquals("Calculator", typeInfo.get("name"));
        assertEquals("class", typeInfo.get("kind"));

        // Verify members — exact counts for Calculator (4 methods, 1 field, 0 nested)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        int totalMembers = ((Number) data.get("totalMembers")).intValue();
        assertEquals(4, methods.size(),
            "Calculator declares 4 methods (add/subtract/multiply/getLastResult); got: " + methods);
        assertEquals(1, fields.size(),
            "Calculator declares 1 field (lastResult); got: " + fields);
        assertEquals(methods.size() + fields.size(), totalMembers,
            "totalMembers must equal methods + fields (Calculator has no nested types); got: "
                + totalMembers);

        // First method has non-blank name and valid signature
        Map<String, Object> first = methods.get(0);
        String name = (String) first.get("name");
        assertNotNull(name, "first method name missing: " + first);
        assertFalse(name.isBlank(), "first method name non-blank; got: " + first);
        String signature = (String) first.get("signature");
        assertNotNull(signature, "first method signature missing: " + first);
        assertTrue(signature.contains(name + "("),
            "signature must start with `<name>(`; got: " + first);
    }

    @Test @DisplayName("supports optional parameters")
    void supportsOptionalParameters() {
        // Test includeInherited
        ObjectNode withInherited = objectMapper.createObjectNode();
        withInherited.put("typeName", "com.example.Calculator");
        withInherited.put("includeInherited", true);
        assertTrue(tool.execute(withInherited).isSuccess());

        // Test memberKind filter
        ObjectNode methodsOnly = objectMapper.createObjectNode();
        methodsOnly.put("typeName", "com.example.Calculator");
        methodsOnly.put("memberKind", "method");
        ToolResponse r = tool.execute(methodsOnly);
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("methods"));
        assertNull(getData(r).get("fields"));
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
    }

    @Test @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown type gracefully")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.Type");

        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator: exact method set (add, subtract, multiply, getLastResult)")
    void calculator_exactDeclaredMethodSet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(java.util.Set.of("add", "subtract", "multiply", "getLastResult"),
            names,
            "Calculator declares exactly these four methods (constructors not included); got: " + names);
    }

    @Test
    @DisplayName("FilledCircle declares draw and fill (both overrides) but excludes inherited Object methods")
    void filledCircle_declaredMethodsOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.FilledCircle");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("draw"));
        assertTrue(names.contains("fill"));
        assertFalse(names.contains("toString"),
            "Without includeInherited, Object methods like toString must not appear; got: " + names);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("memberKind=field returns only fields, omits methods and nestedTypes")
    @SuppressWarnings("unchecked")
    void memberKindField_filtersToFieldsOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("memberKind", "field");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("fields"));
        assertNull(data.get("methods"),
            "methods key must be omitted when memberKind=field; got: " + data);
        assertNull(data.get("nestedTypes"),
            "nestedTypes key must be omitted when memberKind=field; got: " + data);

        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        assertEquals(1, fields.size(),
            "Calculator declares exactly 1 field (lastResult); got: " + fields);
        assertEquals("lastResult", fields.get(0).get("name"));
    }

    @Test
    @DisplayName("memberKind=type returns only nestedTypes, omits methods and fields")
    @SuppressWarnings("unchecked")
    void memberKindType_filtersToNestedTypesOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.TypeKindsFixture");
        args.put("memberKind", "type");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNull(data.get("methods"),
            "methods key must be omitted when memberKind=type; got: " + data);
        assertNull(data.get("fields"),
            "fields key must be omitted when memberKind=type; got: " + data);

        List<Map<String, Object>> nested = (List<Map<String, Object>>) data.get("nestedTypes");
        assertNotNull(nested);
        java.util.Set<String> names = nested.stream()
            .map(t -> (String) t.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        // TypeKindsFixture has Color, GenericContainer, Inner, DefaultMethodHolder, BoundedBox.
        assertTrue(names.contains("Color"),
            "Color enum must appear as nested type; got: " + names);
        assertTrue(names.contains("GenericContainer"));
        assertTrue(names.contains("Inner"));
    }

    @Test
    @DisplayName("includeInherited=true on FilledCircle adds Object methods via super hierarchy")
    @SuppressWarnings("unchecked")
    void includeInherited_addsSuperMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.FilledCircle");
        args.put("includeInherited", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        java.util.Set<String> names = methods.stream()
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // With includeInherited, Object methods toString/equals/hashCode must appear.
        assertTrue(names.contains("toString"),
            "With includeInherited, Object.toString must appear; got: " + names);
        assertTrue(names.contains("equals"),
            "With includeInherited, Object.equals must appear; got: " + names);
        assertTrue(names.contains("hashCode"),
            "With includeInherited, Object.hashCode must appear; got: " + names);
    }

    @Test
    @DisplayName("includeInherited=true: inherited members carry declaredIn pointing to the source type")
    @SuppressWarnings("unchecked")
    void includeInherited_declaredInIsSetToSourceType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.FilledCircle");
        args.put("includeInherited", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        // Find one Object-inherited method and verify declaredIn.
        Map<String, Object> toString = methods.stream()
            .filter(m -> "toString".equals(m.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals("java.lang.Object", toString.get("declaredIn"),
            "Inherited toString must carry declaredIn=java.lang.Object; got: " + toString);
    }

    @Test
    @DisplayName("Method info: signature and modifiers reported for Calculator.add")
    @SuppressWarnings("unchecked")
    void methodInfo_includesSignatureAndModifiers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        Map<String, Object> add = methods.stream()
            .filter(m -> "add".equals(m.get("name")))
            .findFirst()
            .orElseThrow();
        assertNotNull(add.get("signature"));
        List<String> modifiers = (List<String>) add.get("modifiers");
        assertTrue(modifiers.contains("public"));
    }

    @Test
    @DisplayName("Field info: type, modifiers reported for Calculator.lastResult")
    @SuppressWarnings("unchecked")
    void fieldInfo_includesTypeAndModifiers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> fields = (List<Map<String, Object>>) getData(r).get("fields");
        Map<String, Object> lr = fields.stream()
            .filter(f -> "lastResult".equals(f.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals("int", lr.get("type"));
        List<String> modifiers = (List<String>) lr.get("modifiers");
        assertTrue(modifiers.contains("private"));
    }

    @Test
    @DisplayName("Nested type info: SearchPatterns.InnerClass appears with kind=Class")
    @SuppressWarnings("unchecked")
    void nestedTypeInfo_appearsWithKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.SearchPatterns");
        args.put("memberKind", "type");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> nested = (List<Map<String, Object>>) getData(r).get("nestedTypes");
        Map<String, Object> inner = nested.stream()
            .filter(t -> "InnerClass".equals(t.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals("class", inner.get("kind"));
    }
}
