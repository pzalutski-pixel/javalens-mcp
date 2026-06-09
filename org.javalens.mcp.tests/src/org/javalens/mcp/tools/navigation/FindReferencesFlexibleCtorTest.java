package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that find_references reports constructor delegations that are not the
 * first statement of a JEP 513 flexible constructor body. JDT's indexed search
 * misses these; SearchService supplements them with an AST scan. The fixture has
 * two subclasses of {@code Base}: {@code Traditional} calls {@code super(v)} as
 * the first statement (found by the index), {@code Derived} calls it after a
 * pre-super guard (found only by the supplement). Both must be reported.
 */
class FindReferencesFlexibleCtorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReferencesTool tool;
    private String basePath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new FindReferencesTool(() -> service);
        basePath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ctor/Base.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("both first-statement and not-first super(...) delegations are found")
    @SuppressWarnings("unchecked")
    void flexibleCtor_bothDelegationsFound() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", basePath);
        args.put("line", 4);    // 0-based; file line 5 = "public Base(int x) {"
        args.put("column", 11); // the constructor name "Base"

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> locations = (List<Map<String, Object>>) data.get("locations");

        boolean traditional = locations.stream()
            .anyMatch(l -> String.valueOf(l.get("filePath")).endsWith("Traditional.java"));
        boolean derived = locations.stream()
            .anyMatch(l -> String.valueOf(l.get("filePath")).endsWith("Derived.java"));

        assertTrue(traditional,
            "the first-statement super(v) in Traditional must be found; got: " + locations);
        assertTrue(derived,
            "the not-first super(v) in Derived (flexible body) must be found; got: " + locations);
        assertEquals(2, ((Number) data.get("totalCount")).intValue(),
            "exactly the two super(v) delegations are references to Base(int); got: " + locations);
    }
}
