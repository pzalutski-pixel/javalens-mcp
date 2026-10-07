package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.javalens.mcp.tools.GetJavadocTool;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS_TYPE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * A position inside an initializer that is on no symbol resolves to the initializer, which has
 * no name: every tool reports it as {@code <initializer>}, and rename_symbol refuses it, as it
 * refuses an anonymous class.
 *
 * <p>Line 13, column 4 of Events.java is whitespace inside the static initializer, whose source
 * range starts at {@code static} (line 12, column 4).
 */
class InitializerNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String events;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedElements.FIXTURE);
        envelope = new EnvelopeHarness(service);
        events = helper.getFixturePath(UnnamedElements.FIXTURE).resolve(EVENTS).toString();
    }

    @Test
    @DisplayName("get_symbol_info in the static initializer names it <initializer>")
    void symbolInfo() {
        Map<String, Object> data = map(
            "name", "<initializer>",
            "kind", "initializer",
            "modifiers", List.of("static"),
            "declaringType", EVENTS_TYPE,
            "filePath", EVENTS,
            "line", 12,
            "column", 4);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "find_references to find all usages",
            "go_to_definition to navigate to definition",
            "get_type_hierarchy for type inheritance"));
        assertPayload(mapper, new GetSymbolInfoTool(() -> service), envelope, initializer(), success(data, meta));
    }

    @Test
    @DisplayName("get_hover_info in the static initializer names it <initializer>")
    void hoverInfo() {
        Map<String, Object> data = map(
            "name", "<initializer>",
            "kind", "initializer",
            "modifiers", List.of("static"),
            "declaringType", EVENTS_TYPE,
            "filePath", EVENTS);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "go_to_definition to navigate to source",
            "find_references to find all usages",
            "get_javadoc for full documentation"));
        assertPayload(mapper, new GetHoverInfoTool(() -> service), envelope, initializer(), success(data, meta));
    }

    @Test
    @DisplayName("go_to_definition in the static initializer names it <initializer> at its declaration")
    void definition() {
        Map<String, Object> data = map(
            "symbol", "<initializer>",
            "kind", "initializer",
            "location", map("filePath", EVENTS, "line", 12, "column", 4));
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "find_references to see all usages of this symbol",
            "get_type_hierarchy if this is a type",
            "get_type_members to see all members of the containing type"));
        assertPayload(mapper, new GoToDefinitionTool(() -> service), envelope, initializer(), success(data, meta));
    }

    @Test
    @DisplayName("find_references in the static initializer names it <initializer>, which nothing references")
    void references() {
        Map<String, Object> data = map(
            "symbol", "<initializer>",
            "symbolKind", "initializer",
            "totalCount", 0,
            "locations", List.of());
        Map<String, Object> meta = map(
            "totalCount", 0,
            "returnedCount", 0,
            "truncated", false,
            "suggestedNextTools", List.of(
                "go_to_definition to see the symbol definition",
                "get_type_hierarchy for type symbols"));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, initializer(), success(data, meta));
    }

    @Test
    @DisplayName("get_javadoc in the static initializer names it <initializer>, which has no documentation")
    void javadoc() {
        Map<String, Object> data = map("symbol", "<initializer>", "kind", "initializer", "hasDocumentation", false);
        Map<String, Object> meta = map("suggestedNextTools", List.of("get_hover_info for basic info"));
        assertPayload(mapper, new GetJavadocTool(() -> service), envelope, initializer(), success(data, meta));
    }

    @Test
    @DisplayName("get_enclosing_element in the static initializer names the element <initializer> within Events")
    void enclosingElement() {
        Map<String, Object> data = map(
            "element", map("name", "<initializer>", "kind", "initializer"),
            "enclosingType", map(
                "name", "Events",
                "qualifiedName", EVENTS_TYPE,
                "kind", "class",
                "modifiers", List.of("public"),
                "line", 6),
            "enclosingPackage", "com.example.unnamed",
            "filePath", EVENTS);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "get_type_members for type members",
            "get_document_symbols for file structure"));
        assertPayload(mapper, new GetEnclosingElementTool(() -> service), envelope, initializer(),
            success(data, meta));
    }

    @Test
    @DisplayName("analyze_change_impact in the static initializer names it <initializer>, which nothing references")
    void changeImpact() {
        Map<String, Object> data = map(
            "symbol", "<initializer>",
            "symbolType", "Initializer",
            "depth", 1,
            "directReferences", 0,
            "totalReferences", 0,
            "affectedFiles", List.of(),
            "callSites", List.of());
        Map<String, Object> meta = map(
            "totalCount", 0,
            "returnedCount", 0,
            "suggestedNextTools", List.of(
                "rename_symbol for safe renaming across all affected files",
                "find_references for simple reference list without grouping"));
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, initializer(),
            success(data, meta));
    }

    @Test
    @DisplayName("analyze_change_impact transitive in the static initializer is INVALID_PARAMETER naming <initializer>")
    void transitiveChangeImpact() {
        ObjectNode args = initializer();
        args.put("transitive", true);
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'transitive': transitive analysis supports project methods, "
                + "fields, and types; no graph node for '<initializer>'"));
    }

    @Test
    @DisplayName("find_affected_tests in the static initializer is INVALID_PARAMETER naming <initializer>")
    void affectedTests() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, initializer(),
            invalidParameter("Invalid parameter 'position': affected-test analysis supports project methods, "
                + "fields, and types; no graph node for '<initializer>'"));
    }

    @Test
    @DisplayName("rename_symbol in the static initializer is INVALID_PARAMETER: an initializer has no name to rename")
    void renameInitializer() {
        ObjectNode args = initializer();
        args.put("newName", "renamed");
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'position': Symbol at position is an initializer, "
                + "which has no name to rename"));
    }

    @Test
    @DisplayName("rename_symbol inside anonymous class 1 is INVALID_PARAMETER: an anonymous class has no name to rename")
    void renameAnonymousClass() {
        ObjectNode args = position(33, 0);
        args.put("newName", "renamed");
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'position': Symbol at position is the anonymous class "
                + EVENTS_TYPE + "$1, which has no name to rename"));
    }

    private ObjectNode initializer() {
        return position(13, 4);
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
