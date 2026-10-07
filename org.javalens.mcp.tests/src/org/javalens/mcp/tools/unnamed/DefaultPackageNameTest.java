package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.LOOSE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.without;

/**
 * The default package is reported as "(default package)" wherever a tool reports a package
 * name. Loose (Loose.java, no package declaration) is a public class with the one public method
 * {@code greet(): String} on line 2 and nothing else.
 */
class DefaultPackageNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String DEFAULT_PACKAGE = "(default package)";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String loose;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedElements.FIXTURE);
        envelope = new EnvelopeHarness(service);
        loose = helper.getFixturePath(UnnamedElements.FIXTURE).resolve(LOOSE).toString();
    }

    @Test
    @DisplayName("analyze_type of Loose reports its package as (default package)")
    void analyzeType() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "Loose");
        Map<String, Object> data = map(
            "type", map(
                "name", "Loose",
                "qualifiedName", "Loose",
                "package", DEFAULT_PACKAGE,
                "kind", "class",
                "modifiers", List.of("public"),
                "file", LOOSE,
                "line", 0),
            "members", map(
                "constructors", List.of(),
                "methods", List.of(map(
                    "name", "greet",
                    "modifiers", List.of("public"),
                    "signature", "greet(): String",
                    "returnType", "String",
                    "line", 2)),
                "fields", List.of(),
                "nestedTypes", List.of(),
                "constructorCount", 0,
                "methodCount", 1,
                "fieldCount", 0,
                "nestedTypeCount", 0),
            // Loose extends only Object, which is not reported, and has no subtypes.
            "hierarchy", map(),
            "usages", map("instantiations", 0, "casts", 0, "instanceofChecks", 0, "typeArguments", 0, "total", 0),
            "diagnostics", map("errorCount", 0, "warningCount", 0, "hasProblems", false));
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "analyze_method for detailed method analysis",
            "find_references for specific symbol usages",
            "get_call_hierarchy_incoming for method callers"));
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args, success(data, meta));
    }

    @Test
    @DisplayName("go_to_definition on Loose reports its package as (default package)")
    void definition() {
        Map<String, Object> data = map(
            "symbol", "Loose",
            "kind", "class",
            "location", map("filePath", LOOSE, "line", 0, "column", 0),
            "package", DEFAULT_PACKAGE);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "find_references to see all usages of this symbol",
            "get_type_hierarchy if this is a type",
            "get_type_members to see all members of the containing type"));
        assertPayload(mapper, new GoToDefinitionTool(() -> service), envelope, position(0, 13), success(data, meta));
    }

    @Test
    @DisplayName("extract_interface from Loose reports its package as (default package) and writes no package declaration")
    void extractInterface() {
        ObjectNode args = position(0, 13);
        args.put("interfaceName", "Greeter");
        // The implements clause goes right after the class name, which ends at column 18. Raw
        // offsets depend on line endings, so they are removed; line and column pin the edit.
        Map<String, Object> data = map(
            "className", "Loose",
            "interfaceName", "Greeter",
            "packageName", DEFAULT_PACKAGE,
            "interfaceFilePath", "src/main/java/Greeter.java",
            "interfaceContent", "public interface Greeter {\n\n    String greet();\n\n}\n",
            "extractedMethods", List.of(map("name", "greet", "signature", "String greet()")),
            "classEdits", List.of(map("type", "insert", "line", 0, "column", 18, "newText", " implements Greeter")),
            "sourceFilePath", LOOSE);
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Create the interface file with the provided content",
                "Apply the classEdits to add implements clause",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, args, success(data, meta),
            without("offset", "data", "classEdits"));
    }

    @Test
    @DisplayName("suggest_imports for Loose reports the candidate's package as (default package)")
    void suggestImports() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "Loose");
        // No JDK or project type other than Loose is named Loose; its package matches no ranked
        // prefix, so its relevance is the default 30.
        Map<String, Object> data = map(
            "typeName", "Loose",
            "totalCandidates", 1,
            "candidates", List.of(map(
                "fullyQualifiedName", "Loose",
                "packageName", DEFAULT_PACKAGE,
                "simpleName", "Loose",
                "relevance", 30,
                "isInterface", false,
                "isClass", true,
                "isEnum", false,
                "fixId", "add_import:Loose")));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "apply_quick_fix with fixId to add the import",
                "get_quick_fixes to see all available fixes"));
        assertPayload(mapper, new SuggestImportsTool(() -> service), envelope, args, success(data, meta));
    }

    @Test
    @DisplayName("get_dependency_graph of Loose reports its node's package as (default package)")
    void dependencyGraph() {
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "Loose");
        // Loose's only dependency, String (greet's return type), is external and excluded.
        Map<String, Object> data = map(
            "scope", "type",
            "root", "Loose",
            "nodes", List.of(map("name", "Loose", "kind", "class", "package", DEFAULT_PACKAGE)),
            "edges", List.of(),
            "summary", map("totalNodes", 1, "totalEdges", 0, "internalDependencies", 0, "externalDependencies", 0));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "find_circular_dependencies to detect cycles",
                "get_type_hierarchy for inheritance analysis"));
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, args, success(data, meta));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", loose);
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
