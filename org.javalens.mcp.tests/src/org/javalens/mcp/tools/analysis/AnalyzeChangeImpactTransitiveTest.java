package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins analyze_change_impact's transitive mode (issue #20: full blast radius)
 * against the reachability-maven fixture. The search-BFS depth mode is pinned
 * by AnalyzeChangeImpactToolTest and stays unchanged; transitive=true switches
 * to the project graph's reverse closure, which crosses override declarations
 * and has no depth ceiling.
 */
class AnalyzeChangeImpactTransitiveTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeChangeImpactTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("reachability-maven");
        tool = new AnalyzeChangeImpactTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("reachability-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode argsAt(String relativePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve(relativePath).toString());
        args.put("line", line);
        args.put("column", column);
        args.put("transitive", true);
        return args;
    }

    @Test
    @DisplayName("transitive closure crosses interface dispatch with no depth ceiling")
    void transitive_crossesOverrideDeclarations() {
        // prefix <- EnglishGreeter.greet <- Greeter.greet <- App.run <- Main.main
        // is an effective depth of 4 through an override hop: out of reach for
        // the depth<=3 search mode, exact for the graph closure.
        ToolResponse r = tool.execute(
            argsAt("src/main/java/com/reach/EnglishGreeter.java", 13, 20));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("prefix", data.get("symbol"));
        assertEquals(Boolean.TRUE, data.get("transitive"));
        assertEquals(List.of(
            "com.reach.App#run(String)",
            "com.reach.EnglishGreeter#greet(String)",
            "com.reach.GreeterDispatchTest#disabledGreeting()",
            "com.reach.GreeterDispatchTest#greetsThroughInterface()",
            "com.reach.Main#main(String[])"),
            data.get("affectedMethods"));
        assertEquals(5, data.get("totalAffectedMethods"));
    }

    @Test
    @DisplayName("transitive closure walks test helper chains and groups affected files")
    void transitive_exactMethodsAndFiles() {
        ToolResponse r = tool.execute(
            argsAt("src/main/java/com/reach/TestedOnly.java", 8, 16));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("onlyFromTest", data.get("symbol"));
        assertEquals(List.of(
            "com.reach.TestedOnlyTest#doublesInput()",
            "com.reach.TestedOnlyTest#helper()",
            "com.reach.TestedOnlyTest#viaHelper()"),
            data.get("affectedMethods"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affectedFiles = (List<Map<String, Object>>) data.get("affectedFiles");
        assertEquals(1, affectedFiles.size());
        assertEquals("src/test/java/com/reach/TestedOnlyTest.java", affectedFiles.get(0).get("filePath"));
        assertEquals(3, affectedFiles.get(0).get("methodCount"));
    }

    @Test
    @DisplayName("a symbol with no callers returns empty sets, not an error")
    void transitive_noCallers_emptySuccess() {
        ToolResponse r = tool.execute(
            argsAt("src/main/java/com/reach/Main.java", 8, 24));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("main", data.get("symbol"));
        assertEquals(List.of(), data.get("affectedMethods"));
        assertEquals(0, data.get("totalAffectedMethods"));
    }

    @Test
    @DisplayName("transitive mode caps affectedMethods at maxResults with true totals")
    void transitive_maxResultsTruncates() {
        ObjectNode args = argsAt("src/main/java/com/reach/EnglishGreeter.java", 13, 20);
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        Map<String, Object> data = getData(r);
        assertEquals(List.of(
            "com.reach.App#run(String)",
            "com.reach.EnglishGreeter#greet(String)"),
            data.get("affectedMethods"));
        assertEquals(5, data.get("totalAffectedMethods"));
        assertEquals(5, r.getMeta().getTotalCount());
        assertEquals(2, r.getMeta().getReturnedCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    @Test
    @DisplayName("depth mode is unaffected by the new option: default response keeps its shape")
    void depthMode_shapeUnchanged() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/reach/TestedOnly.java").toString());
        args.put("line", 8);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        Map<String, Object> data = getData(r);
        assertEquals(1, ((Number) data.get("depth")).intValue());
        assertNotNull(data.get("callSites"), "depth mode keeps callSites");
        assertNull(data.get("affectedMethods"), "depth mode has no affectedMethods");
        assertNull(data.get("transitive"), "depth mode has no transitive flag");
    }
}
