package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins analyze_type on a JEP 512 compact source file. analyze_type resolves the
 * type by name through the JDT model, which names the implicit class after the
 * file; its members (main, message, greeting) must be reported.
 */
class AnalyzeTypeCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeTypeTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new AnalyzeTypeTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: implicit class resolves with its members")
    @SuppressWarnings("unchecked")
    void compactSource_implicitTypeResolves() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "CompactMain");
        args.put("includeUsages", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("CompactMain", type.get("name"),
            "implicit class resolves by its file-derived name; got: " + type);

        Map<String, Object> members = (Map<String, Object>) data.get("members");
        List<Map<String, Object>> methods = (List<Map<String, Object>>) members.get("methods");
        List<String> methodNames = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(methodNames.contains("main") && methodNames.contains("message"),
            "members.methods must include main and message; got: " + methodNames);

        List<Map<String, Object>> fields = (List<Map<String, Object>>) members.get("fields");
        List<String> fieldNames = fields.stream().map(f -> (String) f.get("name")).toList();
        assertTrue(fieldNames.contains("greeting"),
            "members.fields must include greeting; got: " + fieldNames);
    }
}
