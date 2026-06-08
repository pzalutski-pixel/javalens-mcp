package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins find_circular_dependencies handling of JEP 511 module imports.
 *
 * <p>The java25-maven fixture has a real package cycle
 * {@code com.example.cyc.a <-> com.example.cyc.b}, and {@code cyc.a.A} also
 * carries {@code import module java.base;}. The module import names a module,
 * not a package, so it must not be derived into a package dependency. This run
 * proves both halves: the real cross-package cycle is still detected, and the
 * module import adds no spurious java.* package to the cycle graph.
 */
class FindCircularDependenciesModuleImportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindCircularDependenciesTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new FindCircularDependenciesTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("real package cycle is detected; module import adds no java.* package")
    @SuppressWarnings("unchecked")
    void moduleImport_realCycleDetected_noSpuriousPackage() {
        ObjectNode args = mapper.createObjectNode();
        args.put("packageFilter", "com.example.cyc");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("hasCycles"),
            "the com.example.cyc.a <-> com.example.cyc.b cycle must be detected; got: " + data);
        assertEquals(1, ((Number) data.get("cycleCount")).intValue(),
            "exactly one cycle expected; got: " + data);

        List<String> affected = (List<String>) data.get("affectedPackages");
        assertTrue(affected.contains("com.example.cyc.a") && affected.contains("com.example.cyc.b"),
            "both real packages must be in the cycle; got: " + affected);
        assertEquals(2, affected.size(),
            "module import must not drag a third package into the cycle; got: " + affected);
        assertFalse(affected.stream().anyMatch(p -> p.startsWith("java")),
            "module import 'java.base' must not appear as an affected package; got: " + affected);
    }
}
