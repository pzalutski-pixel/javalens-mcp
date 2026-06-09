package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins get_complexity_metrics on a JEP 512 compact source file. The methods of
 * the implicitly declared class must be measured; the implicit class is an
 * {@code ImplicitTypeDeclaration} (not a {@code TypeDeclaration}), so a visitor
 * keyed only on {@code TypeDeclaration} would silently report zero methods.
 */
class GetComplexityMetricsCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetComplexityMetricsTool tool;
    private String compactMainPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        compactMainPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/CompactMain.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: implicit class methods are measured")
    @SuppressWarnings("unchecked")
    void compactSource_methodsMeasured() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", compactMainPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(2, ((Number) summary.get("methodCount")).intValue(),
            "main() and message() of the implicit class must be measured; got: " + summary);

        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        List<String> names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("main") && names.contains("message"),
            "per-method breakdown must include the implicit class methods; got: " + names);
        assertTrue(methods.stream().allMatch(m -> "CompactMain".equals(m.get("type"))),
            "methods must be attributed to the implicit type CompactMain; got: " + methods);
    }
}
