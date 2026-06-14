package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.SemanticAssertions;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractInterfaceTool.
 * Tests interface generation from class.
 */
class ExtractInterfaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractInterfaceTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String interfaceTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractInterfaceTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        interfaceTargetPath = projectPath.resolve("src/main/java/com/example/InterfaceExtractTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getExtractedMethods(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("extractedMethods");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts interface from class with complete response")
    void extractsInterfaceWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);  // Class declaration line (0-based)
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify interface info
        assertEquals("IExtractTarget", data.get("interfaceName"));
        String content = (String) data.get("interfaceContent");
        assertNotNull(content, "interfaceContent missing");
        assertTrue(content.contains("package com.example"));
        assertTrue(content.contains("public interface IExtractTarget"));

        // Verify public methods are included
        List<Map<String, Object>> methods = getExtractedMethods(data);
        assertFalse(methods.isEmpty());

        // Verify class edits for implements clause — non-empty list with implements text
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classEdits = (List<Map<String, Object>>) data.get("classEdits");
        assertNotNull(classEdits, "classEdits missing");
        assertFalse(classEdits.isEmpty(),
            "extracting an interface must add `implements <name>` to the class; got: " + data);
    }

    @Test
    @DisplayName("excludes private and static methods from interface")
    void excludesPrivateAndStaticMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> methods = getExtractedMethods(data);

        // Should NOT include helper() which is private
        boolean hasHelper = methods.stream()
            .anyMatch(m -> ((String) m.get("name")).contains("helper"));
        assertFalse(hasHelper);

        // Should NOT include create() which is static
        boolean hasCreate = methods.stream()
            .anyMatch(m -> ((String) m.get("name")).contains("create"));
        assertFalse(hasCreate);
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("allows selecting specific methods for interface")
    void allowsSelectingSpecificMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        args.set("methodNames", objectMapper.createArrayNode().add("getName").add("setName"));

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> methods = getExtractedMethods(data);
        assertEquals(2, methods.size());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires interfaceName parameter")
    void requiresInterfaceName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        // No interfaceName

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid interface names")
    void rejectsInvalidInterfaceNames() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "123Invalid");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 999);  // Way beyond file length
        args.put("column", 999);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    @Test
    @DisplayName("default extraction returns exact set of non-private non-static public method names")
    void defaultExtraction_returnsExactPublicMethodSet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        Map<String, Object> data = SemanticAssertions.assertSuccessData(tool.execute(args));
        List<Map<String, Object>> methods = getExtractedMethods(data);

        Set<String> names = SemanticAssertions.fieldSet(methods, "name");
        assertEquals(
            Set.of("getName", "setName", "getValue", "process", "validate", "getItems", "compareTo"),
            names,
            "Default extraction yields exactly the seven non-private non-static public methods " +
            "(constructor, helper(), create(), protectedMethod(), toString() excluded)");
    }

    @Test
    @DisplayName("interface content includes throws clause for validate()")
    void interfaceContent_includesThrowsClause() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        args.set("methodNames", objectMapper.createArrayNode().add("validate"));

        Map<String, Object> data = SemanticAssertions.assertSuccessData(tool.execute(args));
        String content = (String) data.get("interfaceContent");
        assertTrue(content.contains("void validate()"),
            "validate() declaration must appear; got: " + content);
        assertTrue(content.contains("throws IllegalArgumentException"),
            "validate() must declare 'throws IllegalArgumentException'; got: " + content);
    }

    @Test
    @DisplayName("interface content preserves parameter types and names for process(String, int)")
    void interfaceContent_preservesParameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        args.set("methodNames", objectMapper.createArrayNode().add("process"));

        Map<String, Object> data = SemanticAssertions.assertSuccessData(tool.execute(args));
        String content = (String) data.get("interfaceContent");
        assertTrue(content.contains("void process(String input, int count)"),
            "process method signature must preserve parameter types and names; got: " + content);
    }

    @Test
    @DisplayName("class edits add 'implements IExtractTarget' to existing implements list")
    void classEdits_extendExistingImplementsList() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        Map<String, Object> data = SemanticAssertions.assertSuccessData(tool.execute(args));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("classEdits");
        assertNotNull(edits);
        assertFalse(edits.isEmpty(),
            "Class must receive an edit to add the new interface to its implements clause");

        boolean mentionsIExtractTarget = edits.stream().anyMatch(e -> {
            Object newText = e.get("newText");
            return newText != null && newText.toString().contains("IExtractTarget");
        });
        assertTrue(mentionsIExtractTarget,
            "At least one edit must reference IExtractTarget; got: " + edits);
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode targetArgs(String iface) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", iface);
        return args;
    }

    @Test
    @DisplayName("packageName, className, sourceFilePath, interfaceFilePath all reported")
    void responseShape_includesIdentityFields() {
        Map<String, Object> data = SemanticAssertions.assertSuccessData(
            tool.execute(targetArgs("IExtractTarget")));
        assertEquals("InterfaceExtractTarget", data.get("className"));
        assertEquals("IExtractTarget", data.get("interfaceName"));
        assertEquals("com.example", data.get("packageName"));
        String iPath = ((String) data.get("interfaceFilePath")).replace('\\', '/');
        String sPath = ((String) data.get("sourceFilePath")).replace('\\', '/');
        assertTrue(iPath.endsWith("/com/example/IExtractTarget.java"),
            "interfaceFilePath must be sibling .java path; got: " + iPath);
        assertTrue(sPath.endsWith("/com/example/InterfaceExtractTarget.java"),
            "sourceFilePath must be the original class file; got: " + sPath);
    }

    @Test
    @DisplayName("interfaceContent begins with package declaration and public interface block")
    void interfaceContent_structure() {
        Map<String, Object> data = SemanticAssertions.assertSuccessData(
            tool.execute(targetArgs("IExtractTarget")));
        String content = (String) data.get("interfaceContent");
        assertTrue(content.startsWith("package com.example;\n"),
            "Interface content must open with the package declaration; got: " + content);
        assertTrue(content.contains("public interface IExtractTarget {"),
            "Interface content must declare `public interface IExtractTarget {`; got: " + content);
        assertTrue(content.trim().endsWith("}"),
            "Interface content must close with `}`; got: " + content);
    }

    @Test
    @DisplayName("Each extractedMethods entry carries name + signature, with signature matching `<retType> <name>(...)`")
    void extractedMethods_haveSignatureShape() {
        Map<String, Object> data = SemanticAssertions.assertSuccessData(
            tool.execute(targetArgs("IExtractTarget")));
        List<Map<String, Object>> methods = getExtractedMethods(data);
        for (Map<String, Object> m : methods) {
            assertNotNull(m.get("name"), "name missing: " + m);
            assertNotNull(m.get("signature"), "signature missing: " + m);
            String sig = (String) m.get("signature");
            String name = (String) m.get("name");
            assertTrue(sig.contains(name + "("),
                "signature must include `<name>(`; got: " + sig);
        }
    }

    @Test
    @DisplayName("classEdits[0] carries offset, line, column, newText")
    void classEdit_shape() {
        Map<String, Object> data = SemanticAssertions.assertSuccessData(
            tool.execute(targetArgs("IExtractTarget")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("classEdits");
        assertFalse(edits.isEmpty());
        Map<String, Object> e = edits.get(0);
        for (String key : List.of("type", "offset", "line", "column", "newText")) {
            assertNotNull(e.get(key), key + " missing on classEdit: " + e);
        }
        assertEquals("insert", e.get("type"));
    }

    @Test
    @DisplayName("Position on an interface declaration is rejected (cannot extract from interface)")
    void positionOnInterface_isRejected() {
        // IShape.java line 4 (0-based) is `public interface IShape {`
        String iShapePath = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", iShapePath);
        args.put("line", 4);
        args.put("column", 17);
        args.put("interfaceName", "IShapeExt");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        String msg = r.getError() != null ? r.getError().getMessage() : "";
        assertTrue(msg.toLowerCase().contains("interface"),
            "Error message must mention interface; got: " + msg);
    }

    @Test
    @DisplayName("Empty methodName parameter rejected")
    void rejectsEmptyInterfaceName() {
        ObjectNode args = targetArgs("");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("methodNames filter with no matching methods → invalidParameter (no eligible methods)")
    void noEligibleMethods_isRejected() {
        ObjectNode args = targetArgs("IExtractTarget");
        args.set("methodNames", objectMapper.createArrayNode().add("nonExistentMethod"));
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Filtering to a non-existent method yields zero eligible methods and must error");
    }

    // ========== Generics propagation ==========

    @Test
    @DisplayName("Method-level type parameters appear in the extracted signature (`<U extends ...> U identity(U)`)")
    void interfaceContent_includesMethodLevelTypeParameters() {
        // A generic method's `<U extends Comparable<U>>` clause is part of
        // the method signature. Dropping it produces uncompilable Java —
        // the bare `U` references an undeclared type variable.
        String generic = projectPath.resolve("src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IGeneric");
        args.set("methodNames", objectMapper.createArrayNode().add("identity"));

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String content = (String) getData(r).get("interfaceContent");
        assertTrue(content.contains("<U"),
            "Extracted interface body must include the method's `<U` type-parameter clause; got: " + content);
        assertTrue(content.contains("U identity(U "),
            "identity signature must use U in return type and parameter; got: " + content);
    }

    @Test
    @DisplayName("Class-level type parameters propagate to the interface declaration (`public interface IGeneric<T extends Number>`)")
    void interfaceDeclaration_propagatesClassTypeParameters() {
        // A class `Foo<T extends Number>` whose methods reference T cannot
        // be extracted into a non-generic interface — the interface's
        // method signatures would reference an undeclared T. The new
        // interface must carry the same type-parameter list (with bounds),
        // and the class's implements clause must apply the type arguments.
        String generic = projectPath.resolve("src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IGeneric");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        String content = (String) data.get("interfaceContent");
        assertTrue(content.contains("public interface IGeneric<T extends Number>"),
            "Interface declaration must propagate the class's bounded type parameter; got: " + content);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("classEdits");
        assertFalse(edits.isEmpty(), "classEdits must include an implements edit");
        boolean parameterized = edits.stream()
            .map(e -> (String) e.get("newText"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(t -> t.contains("IGeneric<T>"));
        assertTrue(parameterized,
            "implements clause must apply the type argument `IGeneric<T>`, not bare `IGeneric`; got: " + edits);
    }

    @Test
    @DisplayName("Extraction from an enum source is rejected with INVALID_PARAMETER")
    void positionOnEnum_isRejected() {
        // Source line 135-137: type.isEnum() short-circuits with
        // "Cannot extract interface from an enum". Use TypeKindsFixture.Color (a nested
        // enum) — but position must resolve to the enum type itself, so try the top-level
        // enum dimension via the nested Color enum's source range. Simpler: use any
        // top-level enum if one exists. Looking at fixtures: no top-level enum, but
        // Color is nested. The tool calls service.getTypeAtPosition which walks
        // ancestors, so positioning on a Color member should surface Color.
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // Read source to find the line of the Color enum declaration.
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        int idx = source.indexOf("enum Color");
        assertTrue(idx > 0, "TypeKindsFixture must declare nested enum Color");
        int colorNameIdx = source.indexOf("Color", idx);
        int line = (int) source.substring(0, colorNameIdx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', colorNameIdx) + 1;
        int column = colorNameIdx - lineStart;

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);
        args.put("interfaceName", "IColor");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Extraction from enum type must fail with invalidParameter; got success");
        String msg = r.getError() != null ? r.getError().getMessage() : "";
        assertTrue(msg.toLowerCase().contains("enum"),
            "Error message must mention enum; got: " + msg);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: default extraction yields exactly the 7 public method names")
    void envelope_defaultExtraction_exactPublicMethodSet() {
        ObjectNode args = envelope.args();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        JsonNode payload = envelope.payload("extract_interface", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "extract_interface failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("IExtractTarget", data.get("interfaceName").asText());
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (JsonNode m : data.get("extractedMethods")) names.add(m.get("name").asText());
        assertEquals(
            java.util.Set.of("getName", "setName", "getValue", "process", "validate", "getItems", "compareTo"),
            names,
            "the exact non-private non-static public method set must survive the envelope; got: " + names);
    }
}
