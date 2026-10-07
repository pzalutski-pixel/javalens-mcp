package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins get_complexity_metrics on a JEP 512 compact source file. The methods of
 * the implicitly declared class must be measured; the implicit class is an
 * {@code ImplicitTypeDeclaration} (not a {@code TypeDeclaration}), so a visitor
 * keyed only on {@code TypeDeclaration} would silently report zero methods.
 *
 * <p>CompactMain.java has 12 lines: three /// comment lines, two blank lines, the field
 * greeting, main() on lines 5 to 7 and message() on lines 9 to 11, zero-based. The implicit class
 * is named after the file and lies in the unnamed package.
 */
class GetComplexityMetricsCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetComplexityMetricsTool tool;
    private EnvelopeHarness envelope;
    private String compactMainPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        compactMainPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/CompactMain.java").toString();
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("compact source file: implicit class methods main and message are measured and attributed to CompactMain")
    void compactSource_methodsMeasured() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", compactMainPath);

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", "src/main/java/CompactMain.java");
        file.put("physicalLOC", 12);
        file.put("blankLines", 2);
        file.put("commentLines", 3);
        file.put("codeLOC", 7);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCyclomaticComplexity", 2);
        summary.put("totalCognitiveComplexity", 0);
        summary.put("averageMethodCC", 1.0);
        summary.put("maxMethodCC", 1);
        summary.put("methodCount", 2);
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("highRiskMethods", 0);
        risk.put("mediumRiskMethods", 0);
        risk.put("lowRiskMethods", 2);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", file);
        data.put("summary", summary);
        data.put("riskAssessment", risk);
        data.put("methods", List.of(method("main", 5), method("message", 9)));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", 2);
        meta.put("returnedCount", 2);
        meta.put("suggestedNextTools", List.of("get_dependency_graph to analyze dependencies"));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("success", true);
        expected.put("data", data);
        expected.put("meta", meta);

        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = envelope.assertEnvelopeFidelity("get_complexity_metrics", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** A three-line method of CompactMain without parameters or decisions, declared on {@code line}. */
    private static Map<String, Object> method(String name, int line) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("name", name);
        method.put("type", "CompactMain");
        method.put("line", line);
        method.put("cyclomaticComplexity", 1);
        method.put("cognitiveComplexity", 0);
        method.put("physicalLOC", 3);
        method.put("parameterCount", 0);
        method.put("risk", "low");
        return method;
    }
}
