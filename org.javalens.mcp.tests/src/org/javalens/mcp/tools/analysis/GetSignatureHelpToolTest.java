package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSignatureHelpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSignatureHelpToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetSignatureHelpTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSignatureHelpTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("Calculator.add: signature label, parameter labels, activeSignature/activeParameter, and Javadoc first sentence")
    @SuppressWarnings("unchecked")
    void calculatorAdd_returnsExactSignatureInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // Calculator.add method declaration (0-based)
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // LSP indices: the tool always seeds activeSignature/activeParameter at 0.
        assertEquals(0, ((Number) data.get("activeSignature")).intValue());
        assertEquals(0, ((Number) data.get("activeParameter")).intValue());

        List<Map<String, Object>> signatures = (List<Map<String, Object>>) data.get("signatures");
        // Calculator.add has no overloads — exactly one signature must be returned.
        assertEquals(1, signatures.size(),
            "Calculator declares one method named `add`; signature list must have exactly 1 entry; got: "
                + signatures);

        Map<String, Object> sig = signatures.get(0);
        // The tool builds the label as `name(type name, type name): returnType`. This
        // exact form is the user-visible LSP label; regressions (e.g., dropping the
        // parameter names) must fail this test.
        assertEquals("add(int a, int b): int", sig.get("label"),
            "Signature label must match `name(type name, type name): returnType`; got: " + sig);

        List<Map<String, Object>> parameters = (List<Map<String, Object>>) sig.get("parameters");
        assertEquals(2, parameters.size(),
            "add takes exactly 2 parameters; got: " + parameters);
        assertEquals("int a", parameters.get(0).get("label"));
        assertEquals("int b", parameters.get(1).get("label"));

        // Calculator.add carries a Javadoc beginning "Adds two numbers." — the tool
        // extracts the first sentence as `documentation`.
        assertEquals("Adds two numbers.", sig.get("documentation"),
            "First sentence of Javadoc must be surfaced as documentation; got: " + sig);
    }

    @Test
    @DisplayName("non-overloaded method returns exactly one signature (overload detection coverage gap)")
    void nonOverloadedMethod_exactlyOneSignature() {
        // The tool's intent: when a method has overloads, return ALL of them. simple-maven
        // currently has no overloaded methods, so we can only verify the negative case
        // (no overload => exactly one signature). True overload coverage is a deferred
        // fixture gap.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<?> signatures = (List<?>) getData(r).get("signatures");
        assertEquals(1, signatures.size(),
            "Calculator.add has no overloads; tool must return exactly 1 signature; got: "
                + signatures);
    }

    @Test @DisplayName("requires filePath, line, column parameters (each rejected as INVALID_PARAMETER)")
    void requiresParameters() {
        // Missing filePath -> INVALID_PARAMETER naming filePath.
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        ToolResponse rNoFile = tool.execute(noFile);
        assertFalse(rNoFile.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            rNoFile.getError().getCode());

        // Missing line (defaults to -1, fails the >=0 check) -> INVALID_PARAMETER.
        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        ToolResponse rNoLine = tool.execute(noLine);
        assertFalse(rNoLine.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            rNoLine.getError().getCode());
        assertTrue(rNoLine.getError().getMessage().contains("line"),
            "Error must name `line`; got: " + rNoLine.getError().getMessage());

        // Missing column (defaults to -1) -> INVALID_PARAMETER naming column.
        // Independent guard from line — must surface its own validation, not be
        // shadowed by the earlier line check.
        ObjectNode noColumn = objectMapper.createObjectNode();
        noColumn.put("filePath", calculatorPath);
        noColumn.put("line", 14);
        ToolResponse rNoColumn = tool.execute(noColumn);
        assertFalse(rNoColumn.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            rNoColumn.getError().getCode());
        assertTrue(rNoColumn.getError().getMessage().contains("column"),
            "Error must name `column`; got: " + rNoColumn.getError().getMessage());

        // Blank filePath separate from null.
        ObjectNode blankFp = objectMapper.createObjectNode();
        blankFp.put("filePath", "");
        blankFp.put("line", 14);
        blankFp.put("column", 15);
        ToolResponse rBlank = tool.execute(blankFp);
        assertFalse(rBlank.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            rBlank.getError().getCode());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) {
            throw new AssertionError("`" + identifier + "` not found in " + filePath);
        }
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Overloaded greet (0/1/2 params) returns all three signatures with exact labels")
    @SuppressWarnings("unchecked")
    void overloadedMethod_returnsAllOverloadSignatures() throws Exception {
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // DefaultMethodHolder also has a greet() — we need the outer TypeKindsFixture
        // overloads. Find `public String greet` then advance to the `greet` name within it.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int prefix = source.indexOf("public String greet");
        int greetIdx = source.indexOf("greet", prefix);
        int line = (int) source.substring(0, greetIdx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', greetIdx) + 1;
        int column = greetIdx - lineStart;
        ObjectNode pos = objectMapper.createObjectNode();
        pos.put("filePath", tkf);
        pos.put("line", line);
        pos.put("column", column);
        ToolResponse r = tool.execute(pos);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        List<Map<String, Object>> signatures = (List<Map<String, Object>>) data.get("signatures");
        assertEquals(3, signatures.size(),
            "TypeKindsFixture declares 3 overloads of greet (0, 1, 2 params); got: " + signatures);

        java.util.Set<String> labels = signatures.stream()
            .map(s -> (String) s.get("label"))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of(
                "greet(): String",
                "greet(String name): String",
                "greet(String name, int times): String"),
            labels,
            "All three overload labels must appear; got: " + labels);
    }

    @Test
    @DisplayName("Method with no Javadoc omits documentation field on its signature")
    @SuppressWarnings("unchecked")
    void methodWithoutJavadoc_omitsDocumentation() throws Exception {
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // Same caveat: DefaultMethodHolder also has a greet(). Target the outer
        // TypeKindsFixture overloads via `public String greet` prefix.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int prefix = source.indexOf("public String greet");
        int greetIdx = source.indexOf("greet", prefix);
        int line = (int) source.substring(0, greetIdx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', greetIdx) + 1;
        int column = greetIdx - lineStart;
        ObjectNode pos = objectMapper.createObjectNode();
        pos.put("filePath", tkf);
        pos.put("line", line);
        pos.put("column", column);
        ToolResponse r = tool.execute(pos);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) getData(r).get("signatures");
        // None of the greet overloads have a /** */ Javadoc — none should carry a
        // documentation field.
        for (Map<String, Object> sig : signatures) {
            assertNull(sig.get("documentation"),
                "greet overload without Javadoc must omit documentation field; got: " + sig);
        }
    }

    @Test
    @DisplayName("Long Javadoc with no early period truncates to 200 chars + '...'")
    @SuppressWarnings("unchecked")
    void longJavadocNoEarlyPeriod_truncatesAt200WithEllipsis() throws Exception {
        // TypeKindsFixture.longJavadocNoEarlyPeriod carries a single-line Javadoc with
        // no period and length > 200 characters. createSignatureInfo's
        // `else if (doc.length() > 200)` branch returns substring(0, 200) + "...".
        // Without this test the 200-char truncation contract is uncovered.
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ObjectNode pos = argsAtIdentifier(tkf, "longJavadocNoEarlyPeriod");
        ToolResponse r = tool.execute(pos);
        assertTrue(r.isSuccess(),
            "longJavadocNoEarlyPeriod must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) getData(r).get("signatures");
        Map<String, Object> sig = signatures.stream()
            .filter(s -> "longJavadocNoEarlyPeriod(): void".equals(s.get("label")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected longJavadocNoEarlyPeriod() signature; got: " + signatures));
        String doc = (String) sig.get("documentation");
        assertNotNull(doc, "documentation must be populated; got sig: " + sig);
        // Exact contract: 200 chars + "..." = 203 chars total. Ends with "...".
        assertEquals(203, doc.length(),
            "Truncation must produce exactly 200 + 3 chars; got length " + doc.length()
                + " for: " + doc);
        assertTrue(doc.endsWith("..."),
            "Truncated documentation must end with '...'; got: " + doc);
        assertTrue(doc.startsWith("Single-line Javadoc summary"),
            "Truncated documentation must preserve the start of the Javadoc; got: " + doc);
    }

    @Test
    @DisplayName("Constructor at position: label has no `: ReturnType` suffix")
    @SuppressWarnings("unchecked")
    void constructorPosition_signatureHasNoReturnType() throws Exception {
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String helloPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(helloPath));
        // Find the constructor declaration: pattern is `public HelloWorld(`.
        int idx = source.indexOf("public HelloWorld(");
        idx = source.indexOf("HelloWorld(", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloPath);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) getData(r).get("signatures");
        assertFalse(signatures.isEmpty());
        // Constructor labels are `name(params)` without `: ReturnType`.
        for (Map<String, Object> sig : signatures) {
            String label = (String) sig.get("label");
            assertTrue(label.startsWith("HelloWorld("),
                "Constructor label must start with class name; got: " + label);
            assertFalse(label.contains("):"),
                "Constructor label must NOT have `: ReturnType` suffix; got: " + label);
        }
    }
}
