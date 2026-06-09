package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins apply_quick_fix add_throws against a JEP 513 flexible constructor body.
 * The enclosing constructor is resolved from a body line (the pre-super throw)
 * and the throws clause is inserted after the parameter list — the statement
 * preceding super() must not disrupt where the clause lands.
 */
class ApplyQuickFixFlexibleCtorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyQuickFixTool tool;
    private String flexibleThrowsPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new ApplyQuickFixTool(() -> service);
        flexibleThrowsPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/FlexibleThrows.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("add_throws on a flexible constructor inserts at the signature, from a body line")
    @SuppressWarnings("unchecked")
    void flexibleCtor_addThrowsTargetsSignature() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", flexibleThrowsPath);
        args.put("fixId", "add_throws:IOException");
        args.put("line", 12); // 0-based; file line 13 = the pre-super "throw new IllegalArgumentException();"

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertEquals(1, edits.size(), "exactly one insert edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"));
        assertEquals(" throws IOException", edit.get("newText"),
            "throws clause must be inserted; got: " + edit);
        assertEquals(10, ((Number) edit.get("line")).intValue(),
            "clause lands on the constructor signature line (0-based 10), not the throw line; got: " + edit);
    }
}
