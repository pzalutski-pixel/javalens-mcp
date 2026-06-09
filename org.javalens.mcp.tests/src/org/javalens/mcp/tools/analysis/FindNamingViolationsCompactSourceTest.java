package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins find_naming_violations on a JEP 512 compact source file. Member checks
 * must descend into the implicitly declared class — a PascalCase method name is
 * reported — while the implicit class's own (filename-derived) name must not be
 * checked: the fixture file is lowercase ("badcompact"), so a class-name check
 * would wrongly flag it.
 */
class FindNamingViolationsCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindNamingViolationsTool tool;
    private String badCompactPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new FindNamingViolationsTool(() -> service);
        badCompactPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/badcompact.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: bad method name flagged; implicit class name not checked")
    @SuppressWarnings("unchecked")
    void compactSource_memberCheckedTypeNameNot() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", badCompactPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(r).get("violations");

        boolean methodFlagged = violations.stream().anyMatch(v ->
            "method".equals(v.get("elementType")) && "HelperMethod".equals(v.get("name")));
        assertTrue(methodFlagged,
            "PascalCase method HelperMethod inside the implicit class must be flagged; got: " + violations);

        boolean classFlagged = violations.stream().anyMatch(v -> "class".equals(v.get("elementType")));
        assertFalse(classFlagged,
            "the implicit class's filename-derived name must not be checked as a class name; got: "
                + violations);
    }
}
