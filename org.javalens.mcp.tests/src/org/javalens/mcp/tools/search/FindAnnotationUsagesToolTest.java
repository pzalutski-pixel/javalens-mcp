package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindAnnotationUsagesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindAnnotationUsagesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindAnnotationUsagesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getUsages(Map<String, Object> d) { return (List<?>) d.get("usages"); }

    @Test @DisplayName("finds annotation usages")
    void findsAnnotationUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("annotation", "java.lang.Override");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getUsages(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalUsages"));
        assertEquals("java.lang.Override", getData(r).get("annotation"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("annotation", "java.lang.Override");
        args.put("maxResults", 1);
        assertTrue(getUsages(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires annotation")
    void requiresAnnotation() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown annotation")
    void handlesUnknownAnnotation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("annotation", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("@Marker usages from AnnotationUsages span all 6 documented targets")
    void marker_findsAllDocumentedTargets() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("annotation", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // AnnotationUsages places @Marker on: class, field markedField, constructor,
        // method markedMethod, parameter p in markedParameter, local 'local' inside
        // markedParameter, and the type-use 'List<@Marker String>' return in typeUseUsage.
        // Expect at least 6 usages found (depending on JDT's type-use representation).
        assertTrue(((Number) data.get("totalUsages")).intValue() >= 6,
            "Expected at least 6 @Marker usages across all documented targets; got: "
                + data.get("totalUsages") + " (" + getUsages(data) + ")");
    }
}
