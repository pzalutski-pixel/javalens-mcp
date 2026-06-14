package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeMembersTool(() -> service);
        envelope = new EnvelopeHarness(service);
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
        assertEquals("com.example.Calculator", typeInfo.get("qualifiedName"));
        assertTrue(((String) typeInfo.get("filePath")).replace('\\', '/')
            .endsWith("src/main/java/com/example/Calculator.java"),
            "type block filePath must be fixture-relative; got: " + typeInfo.get("filePath"));

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
        // includeInherited adds Object methods that are absent by default.
        ObjectNode withInherited = objectMapper.createObjectNode();
        withInherited.put("typeName", "com.example.Calculator");
        withInherited.put("includeInherited", true);
        ToolResponse ri = tool.execute(withInherited);
        assertTrue(ri.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inheritedMethods = (List<Map<String, Object>>) getData(ri).get("methods");
        assertTrue(inheritedMethods.stream().anyMatch(m -> "toString".equals(m.get("name"))),
            "includeInherited must surface Object.toString; got: " + inheritedMethods);

        // memberKind=method returns exactly the 4 methods and omits the fields key.
        ObjectNode methodsOnly = objectMapper.createObjectNode();
        methodsOnly.put("typeName", "com.example.Calculator");
        methodsOnly.put("memberKind", "method");
        ToolResponse r = tool.execute(methodsOnly);
        assertTrue(r.isSuccess());
        assertEquals(4, ((List<?>) getData(r).get("methods")).size());
        assertNull(getData(r).get("fields"));
    }

    @Test @DisplayName("simple name resolves to the same type + members as the FQN")
    @SuppressWarnings("unchecked")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, Object> typeInfo = (Map<String, Object>) data.get("type");
        assertEquals("com.example.Calculator", typeInfo.get("qualifiedName"),
            "simple name 'Calculator' must resolve to com.example.Calculator");
        assertEquals(4, ((List<?>) data.get("methods")).size());
        assertEquals(1, ((List<?>) data.get("fields")).size());
    }

    @Test @DisplayName("missing typeName is rejected with INVALID_PARAMETER")
    void requiresTypeName() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain typeName is required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("unknown type is rejected with SYMBOL_NOT_FOUND naming the type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.Type");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("com.nonexistent.Type"),
            "message must name the unresolved type; got: " + r.getError().getMessage());
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

        // FilledCircle declares its explicit constructor plus draw() and fill() (both
        // @Override). Explicitly-declared constructors ARE included; without
        // includeInherited, no Object methods appear.
        assertEquals(java.util.Set.of("FilledCircle", "draw", "fill"), names,
            "FilledCircle declares exactly its constructor + draw + fill; got: " + names);
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
        // TypeKindsFixture declares exactly these five nested types.
        assertEquals(java.util.Set.of("Color", "GenericContainer", "Inner",
            "DefaultMethodHolder", "BoundedBox"), names,
            "exact nested-type set of TypeKindsFixture; got: " + names);
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

        // The full inherited Object method set is JDK-version-variable (e.g. wait/clone
        // overloads), so an exact set is not derivable; assert the always-present subset
        // plus the declared members, and the negative (no spurious draw/fill loss).
        assertTrue(names.containsAll(java.util.Set.of("draw", "fill", "toString", "equals", "hashCode")),
            "With includeInherited, FilledCircle's declared methods + Object toString/equals/hashCode "
                + "must all appear; got: " + names);
    }

    @Test
    @DisplayName("includeInherited=true: inherited members carry declaredIn; binary members omit `line`")
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
        // Object.toString is a BINARY method (no source available). createMethodInfo
        // guards `if (cu != null)` and skips populating `line`. Pins that contract
        // so a future regression that emits line=0 or line=-1 for binary methods
        // would surface here.
        assertFalse(toString.containsKey("line"),
            "Binary inherited method must NOT carry a `line` key (no compilation unit); got: "
                + toString);
        // Direct overrides (draw, fill) come from FilledCircle.java — they DO carry a line.
        Map<String, Object> draw = methods.stream()
            .filter(m -> "draw".equals(m.get("name")))
            .findFirst()
            .orElseThrow();
        assertTrue(draw.containsKey("line"),
            "Direct source-resident method `draw` must carry a `line` key; got: " + draw);
        assertNull(draw.get("declaredIn"),
            "Direct (non-inherited) methods must NOT carry `declaredIn`; got: " + draw);
    }

    @Test
    @DisplayName("memberKind='unknown' filter (not method/field/type) returns no member lists, only the type block + totalMembers=0")
    @SuppressWarnings("unchecked")
    void memberKindUnknown_returnsZeroMembersAndOmitsLists() {
        // Source: the three `if (memberKind == null || "method".equals(memberKind))` /
        // ...field/type guards collectively cover ONLY the documented values. An
        // unknown kind passes through, no collectXxx runs, and no data.put for the
        // lists fires. The tool returns success with `type` and `totalMembers=0`.
        // Pin this contract so a regression that silently fell through to "all"
        // (or returned an error) would surface here.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("memberKind", "unknown");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Unknown memberKind currently returns success (silent no-op), not an error.");
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("type"), "type block must still be present; got: " + data);
        assertFalse(data.containsKey("methods"),
            "methods key must be omitted for unknown memberKind; got: " + data);
        assertFalse(data.containsKey("fields"),
            "fields key must be omitted for unknown memberKind; got: " + data);
        assertFalse(data.containsKey("nestedTypes"),
            "nestedTypes key must be omitted for unknown memberKind; got: " + data);
        assertEquals(0, ((Number) data.get("totalMembers")).intValue(),
            "totalMembers must be 0 when no list was populated; got: " + data);
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
        assertEquals("add(int a, int b): int", add.get("signature"),
            "exact rendered signature; got: " + add.get("signature"));
        assertEquals(List.of("public"), add.get("modifiers"),
            "Calculator.add is exactly `public`; got: " + add.get("modifiers"));
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
        assertEquals(List.of("private"), lr.get("modifiers"),
            "Calculator.lastResult is exactly `private`; got: " + lr.get("modifiers"));
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

    @Test
    @DisplayName("Generic-class member signatures preserve type-variable references")
    @SuppressWarnings("unchecked")
    void genericClass_memberSignatures_preserveTypeVariables() {
        // GenericClass<T> declares:
        //   - field `private T value;`      → type must surface as "T"
        //   - method `public T read()`      → signature must include T as return type
        //   - method `public void set(T v)` → signature must include T as parameter type
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.genericunused.GenericClass");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        Map<String, Object> valueField = fields.stream()
            .filter(f -> "value".equals(f.get("name")))
            .findFirst().orElseThrow();
        assertEquals("T", valueField.get("type"),
            "Field `value` is declared as T; type must report T literally; got: " + valueField);

        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        Map<String, Object> readMethod = methods.stream()
            .filter(m -> "read".equals(m.get("name")))
            .findFirst().orElseThrow();
        String readSig = (String) readMethod.get("signature");
        assertNotNull(readSig);
        assertTrue(readSig.endsWith(": T"),
            "read() returns T; signature must end with `: T`; got: " + readSig);

        Map<String, Object> setMethod = methods.stream()
            .filter(m -> "set".equals(m.get("name")))
            .findFirst().orElseThrow();
        String setSig = (String) setMethod.get("signature");
        assertNotNull(setSig);
        assertTrue(setSig.contains("T "),
            "set(T v) parameter must reference T in signature; got: " + setSig);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator has exactly its 4 declared methods and 1 field")
    void envelope_calculator_exactMembers() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Calculator");
        JsonNode payload = envelope.assertEnvelopeFidelity("get_type_members", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_type_members failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        java.util.Set<String> methodNames = new java.util.TreeSet<>();
        for (JsonNode m : data.get("methods")) methodNames.add(m.get("name").asText());
        assertEquals(java.util.Set.of("add", "subtract", "multiply", "getLastResult"), methodNames,
            "Calculator's exact declared method set must survive the envelope; got: " + methodNames);
        assertEquals(1, data.get("fields").size(), "Calculator has exactly one field");
        assertEquals("lastResult", data.get("fields").get(0).get("name").asText());
    }
}
