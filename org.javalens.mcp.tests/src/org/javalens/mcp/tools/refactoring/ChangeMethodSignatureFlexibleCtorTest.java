package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins change_method_signature against a JEP 513 flexible constructor body: the
 * delegating {@code super(v)} call is not the first statement, yet adding a
 * parameter to the superclass constructor must still locate and rewrite that
 * call site. Call sites are found by offset via a full-AST visitor, so the
 * statement preceding {@code super(...)} does not hide it.
 */
class ChangeMethodSignatureFlexibleCtorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private String basePath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new ChangeMethodSignatureTool(() -> service);
        basePath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ctor/Base.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("adding a param to the superclass ctor rewrites the not-first super(v) call")
    @SuppressWarnings("unchecked")
    void flexibleCtor_superCallSiteRewritten() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", basePath);
        args.put("line", 4);    // 0-based; file line 5 = "public Base(int x) {"
        args.put("column", 11); // the constructor name "Base"

        ArrayNode params = args.putArray("newParameters");
        ObjectNode p1 = params.addObject();
        p1.put("name", "x");
        p1.put("type", "int");
        ObjectNode p2 = params.addObject();
        p2.put("name", "y");
        p2.put("type", "int");
        p2.put("defaultValue", "0");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        Map<String, Object> editsByFile = (Map<String, Object>) data.get("editsByFile");

        String derivedKey = editsByFile.keySet().stream()
            .filter(k -> k.endsWith("Derived.java"))
            .findFirst().orElse(null);
        assertNotNull(derivedKey,
            "the super(v) call site in Derived.java must receive an edit; got files: "
                + editsByFile.keySet());

        List<Map<String, Object>> derivedEdits = (List<Map<String, Object>>) editsByFile.get(derivedKey);
        boolean superRewritten = derivedEdits.stream()
            .map(e -> String.valueOf(e.get("newText")))
            .anyMatch(t -> t.contains("super(") && t.contains("v") && t.contains("0"));
        assertTrue(superRewritten,
            "the not-first super(v) call must be rewritten to pass the new argument; got: " + derivedEdits);
    }
}
