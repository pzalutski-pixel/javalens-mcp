package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindImplementationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindImplementationsTool tool;
    private ObjectMapper objectMapper;
    private String ishapePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindImplementationsTool(() -> service);
        objectMapper = new ObjectMapper();
        ishapePath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/IShape.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getImplementations(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("implementations");
    }

    @Test
    @DisplayName("includes transitive implementors via sub-interface chain")
    void includesTransitiveImplementors() {
        // IShape is at line=2, col=17 in IShape.java
        // Direct: Rectangle (implements IShape), IFillable (extends IShape)
        // Transitive: FilledCircle (implements IFillable which extends IShape)
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", ishapePath);
        args.put("line", 2);
        args.put("column", 17);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> impls = getImplementations(data);
        assertNotNull(impls);
        // Must include the transitive implementor FilledCircle
        assertTrue((int) data.get("totalImplementations") >= 3,
            "Expected IFillable, Rectangle and FilledCircle but got: " + impls.size());

        boolean hasFilledCircle = impls.stream()
            .anyMatch(m -> "FilledCircle".equals(m.get("name")));
        assertTrue(hasFilledCircle, "FilledCircle (transitive via IFillable) must be present");
    }

    @Test
    @DisplayName("returns correct metadata for direct implementors")
    void returnsCorrectMetadataForDirectImplementors() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", ishapePath);
        args.put("line", 2);
        args.put("column", 17);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        List<Map<String, Object>> impls = getImplementations(getData(response));

        // Each entry must have name, qualifiedName, kind, filePath, line, column
        for (Map<String, Object> impl : impls) {
            assertNotNull(impl.get("name"), "name must be set");
            assertNotNull(impl.get("qualifiedName"), "qualifiedName must be set");
            assertNotNull(impl.get("kind"), "kind must be set");
        }
    }

    @Test
    @DisplayName("requires filePath")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 2);
        args.put("column", 17);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test
    @DisplayName("handles non-type position gracefully")
    void handlesNonTypePosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", ishapePath);
        args.put("line", 3);   // method declaration line inside IShape
        args.put("column", 4);
        ToolResponse r = tool.execute(args);
        assertNotNull(r);
    }
}
