package org.javalens.mcp.tools.unnamed;

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

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * get_complexity_metrics names the type of each method through the type's model element when
 * the compiler built no binding for the type, so one such type never aborts the file.
 *
 * <p>Holder.java (duplicate-types, package com.example.dup) declares two static member classes
 * named Part. JDT builds a binding only for the first: the second duplicates a sibling member
 * type (ClassScope.buildMemberTypes reports duplicateNestedType and skips it), so its
 * declaration resolves to no binding, while the Java model holds it as a second element named
 * Part. The first Part declares {@code first()} (lines 5 to 7), the second {@code second()}
 * (lines 11 to 13), each returning a constant. The file has 16 lines, 3 of them blank.
 */
class UnresolvedTypeBindingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String holder;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("duplicate-types");
        envelope = new EnvelopeHarness(service);
        holder = helper.getFixturePath("duplicate-types").resolve("src/main/java/com/example/dup/Holder.java").toString();
    }

    @Test
    @DisplayName("get_complexity_metrics of Holder names the methods of both Part classes com.example.dup.Holder$Part, the second through its model element")
    void duplicateMemberType() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", holder);
        Map<String, Object> data = map(
            "file", map(
                "path", "src/main/java/com/example/dup/Holder.java",
                "physicalLOC", 16,
                "blankLines", 3,
                "commentLines", 0,
                "codeLOC", 13),
            "summary", map(
                "totalCyclomaticComplexity", 2,
                "totalCognitiveComplexity", 0,
                "averageMethodCC", 1.0,
                "maxMethodCC", 1,
                "methodCount", 2),
            "riskAssessment", map("highRiskMethods", 0, "mediumRiskMethods", 0, "lowRiskMethods", 2),
            "methods", List.of(
                method("first", 5),
                method("second", 11)));
        Map<String, Object> meta = map(
            "totalCount", 2,
            "returnedCount", 2,
            "suggestedNextTools", List.of("get_dependency_graph to analyze dependencies"));
        assertPayload(mapper, new GetComplexityMetricsTool(() -> service), envelope, args, success(data, meta));
    }

    private static Map<String, Object> method(String name, int line) {
        return map(
            "name", name,
            "type", "com.example.dup.Holder$Part",
            "line", line,
            "cyclomaticComplexity", 1,
            "cognitiveComplexity", 0,
            "physicalLOC", 3,
            "parameterCount", 0,
            "risk", "low");
    }
}
