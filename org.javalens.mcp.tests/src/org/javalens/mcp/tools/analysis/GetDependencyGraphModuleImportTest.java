package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins get_dependency_graph handling of JEP 511 module imports
 * ({@code import module java.base;}), standard in Java 25.
 *
 * <p>A module import is not a package import: it names a module, not a package,
 * so it must not be turned into a package/type node in the graph. The JDT model
 * surfaces it as an on-demand {@code IImportDeclaration} with element name
 * {@code "java.base.*"}, which the string-stripping derivation would otherwise
 * mis-read as a dependency on package {@code java.base}.
 *
 * <p>The fixture {@code ModuleImportDemo} also has an ordinary
 * {@code import java.util.Map;} so the same run proves ordinary imports are
 * still tracked — the module-import handling must isolate the module import
 * only, not suppress real dependencies.
 */
class GetDependencyGraphModuleImportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDependencyGraphTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetDependencyGraphTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("module import (import module java.base;) is not emitted as a java.base package/type node")
    @SuppressWarnings("unchecked")
    void moduleImport_notDerivedAsPackageNode() {
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.ModuleImportDemo");
        args.put("includeExternal", true); // would surface a bogus java.base node if mis-derived

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) getData(r).get("nodes");

        boolean hasModuleNode = nodes.stream()
            .map(n -> String.valueOf(n.get("name")))
            .anyMatch(name -> name.equals("java.base") || name.startsWith("java.base."));
        assertFalse(hasModuleNode,
            "module import 'java.base' must not appear as a graph node; got: " + nodes);

        boolean hasOrdinaryImport = nodes.stream()
            .map(n -> String.valueOf(n.get("name")))
            .anyMatch(name -> name.equals("java.util.Map"));
        assertTrue(hasOrdinaryImport,
            "ordinary 'import java.util.Map;' must still be tracked as a node; got: " + nodes);
    }
}
