package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins analyze_file on a JEP 512 compact source file: the implicitly declared
 * class and its top-level members must be surfaced, not skipped.
 */
class AnalyzeFileCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeFileTool tool;
    private String compactMainPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new AnalyzeFileTool(() -> service);
        compactMainPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/CompactMain.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: implicit class with its members is reported")
    @SuppressWarnings("unchecked")
    void compactSource_implicitClassReported() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", compactMainPath);
        args.put("includeMembers", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        assertEquals(1, ((Number) data.get("typeCount")).intValue(),
            "the implicit class must be reported as exactly one type; got: " + types);

        Map<String, Object> implicit = types.get(0);
        assertEquals("CompactMain", implicit.get("name"),
            "implicit class is named after the file; got: " + implicit);
        assertEquals(2, ((Number) implicit.get("methodCount")).intValue(),
            "implicit class has main() and message(); got: " + implicit);
        assertEquals(1, ((Number) implicit.get("fieldCount")).intValue(),
            "implicit class has the greeting field; got: " + implicit);

        List<Map<String, Object>> methods = (List<Map<String, Object>>) implicit.get("methods");
        List<String> methodNames = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(methodNames.contains("main") && methodNames.contains("message"),
            "implicit class methods must include main and message; got: " + methodNames);

        List<Map<String, Object>> fields = (List<Map<String, Object>>) implicit.get("fields");
        assertEquals("greeting", fields.get(0).get("name"),
            "implicit class field must be greeting; got: " + fields);
    }
}
