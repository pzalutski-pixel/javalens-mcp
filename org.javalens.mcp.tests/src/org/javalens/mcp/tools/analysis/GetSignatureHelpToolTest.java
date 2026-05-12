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

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());
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
}
