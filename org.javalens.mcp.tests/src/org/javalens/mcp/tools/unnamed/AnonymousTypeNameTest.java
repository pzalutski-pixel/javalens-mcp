package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.javalens.mcp.tools.GetJavadocTool;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
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
import static org.javalens.mcp.tools.unnamed.UnnamedElements.sortedBy;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * An anonymous class is named by its type-qualified name, its qualified name without the
 * package ({@code Events$1}), wherever a tool reports a type's own name; a named class, local
 * or member, keeps its simple name.
 *
 * <p>Line 33 of Events.java is the blank line inside anonymous class 1 (the Runnable returned
 * by listener()), between its field and its method: a position there resolves to the
 * anonymous class itself. Its source range starts at {@code new} (line 31, column 15) and its
 * name range at {@code Runnable} (line 31, column 19). For an anonymous class, the JDT model
 * reports the type named after {@code new} as its superclass and no interfaces
 * (SourceTypeElementInfo), so anonymous class 1's superclass is Runnable. Sink's subtypes come
 * from a type hierarchy, whose order is not specified, so they are compared sorted by qualified
 * name.
 */
class AnonymousTypeNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String ANONYMOUS_1 = EVENTS_TYPE + "$1";
    private static final String ANONYMOUS_3 = EVENTS_TYPE + "$3";

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
    @DisplayName("get_type_at_position inside anonymous class 1 names it Events$1")
    void typeAtPosition() {
        Map<String, Object> data = map(
            "name", "Events$1",
            "qualifiedName", ANONYMOUS_1,
            "kind", "class",
            "modifiers", List.of(),
            "superclass", "Runnable",
            "declaringType", EVENTS_TYPE,
            "isNested", true,
            "isAnonymous", true,
            "isLocal", true,
            "filePath", EVENTS,
            "line", 31,
            "methodCount", 1,
            "fieldCount", 1,
            "nestedTypeCount", 0);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "get_type_hierarchy for inheritance chain",
            "get_type_members for all members",
            "find_implementations for implementors"));
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("get_enclosing_element inside anonymous class 1 names the element and the enclosing type Events$1, within listener() of Events")
    void enclosingElement() {
        Map<String, Object> data = map(
            "element", map("name", "Events$1", "kind", "class"),
            "enclosingMethod", map(
                "name", "listener",
                "isConstructor", false,
                "signature", "listener(): Runnable",
                "modifiers", List.of(),
                "line", 30),
            "enclosingType", map(
                "name", "Events$1",
                "qualifiedName", ANONYMOUS_1,
                "kind", "class",
                "modifiers", List.of(),
                "line", 31),
            "outerType", map(
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
        assertPayload(mapper, new GetEnclosingElementTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("get_type_hierarchy of Sink names its subtypes Recorder and Events$3")
    void typeHierarchy() {
        Map<String, Object> data = map(
            "type", map("name", "Sink", "qualifiedName", EVENTS_TYPE + "$Sink", "kind", "class",
                "filePath", EVENTS, "line", 20),
            "superclasses", List.of(map("name", "Object", "qualifiedName", "java.lang.Object", "kind", "class")),
            "interfaces", List.of(),
            "subtypes", List.of(
                map("name", "Events$3", "qualifiedName", ANONYMOUS_3, "kind", "class", "filePath", EVENTS,
                    "line", 52),
                map("name", "Recorder", "qualifiedName", EVENTS_TYPE + "$Recorder", "kind", "class",
                    "filePath", EVENTS, "line", 24)),
            "totalSuperclasses", 1,
            "totalInterfaces", 0,
            "totalSubtypes", 2);
        Map<String, Object> meta = map(
            "totalCount", 3,
            "returnedCount", 3,
            "truncated", false,
            "suggestedNextTools", List.of(
                "get_type_members to see members of a specific type",
                "find_implementations to find all implementors",
                "find_references to see where type is used"));
        assertPayload(mapper, new GetTypeHierarchyTool(() -> service), envelope,
            position(20, 26), success(data, meta), sortedBy("qualifiedName", "data", "subtypes"));
    }

    @Test
    @DisplayName("find_implementations of Sink names its implementations Recorder and Events$3")
    void implementations() {
        Map<String, Object> data = map(
            "symbol", "Sink",
            "isInterface", false,
            "totalImplementations", 2,
            "implementations", List.of(
                map("name", "Events$3", "qualifiedName", ANONYMOUS_3, "kind", "class", "filePath", EVENTS,
                    "line", 52, "column", 19),
                map("name", "Recorder", "qualifiedName", EVENTS_TYPE + "$Recorder", "kind", "class",
                    "filePath", EVENTS, "line", 24, "column", 17)));
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope,
            position(20, 26), success(data, implementationsMeta(2)),
            sortedBy("qualifiedName", "data", "implementations"));
    }

    @Test
    @DisplayName("find_implementations inside anonymous class 1 names the target Events$1, which has no subtypes")
    void implementationsOfAnonymousClass() {
        Map<String, Object> data = map(
            "symbol", "Events$1",
            "isInterface", false,
            "totalImplementations", 0,
            "implementations", List.of());
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope,
            position(33, 0), success(data, implementationsMeta(0)));
    }

    @Test
    @DisplayName("analyze_type of Events.Sink names its subtypes Recorder and Events$3")
    void analyzeType() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.unnamed.Events.Sink");
        args.put("includeUsages", false);
        Map<String, Object> data = map(
            "type", map(
                "name", "Sink",
                "qualifiedName", EVENTS_TYPE + "$Sink",
                "package", "com.example.unnamed",
                "kind", "class",
                "modifiers", List.of("static", "abstract"),
                "file", EVENTS,
                "line", 20),
            "members", map(
                "constructors", List.of(),
                "methods", List.of(map(
                    "name", "accept",
                    "modifiers", List.of("abstract"),
                    "signature", "accept(String event): void",
                    "returnType", "void",
                    "line", 21)),
                "fields", List.of(),
                "nestedTypes", List.of(),
                "constructorCount", 0,
                "methodCount", 1,
                "fieldCount", 0,
                "nestedTypeCount", 0),
            "hierarchy", map(
                "subtypes", List.of(
                    map("name", "Events$3", "qualifiedName", ANONYMOUS_3),
                    map("name", "Recorder", "qualifiedName", EVENTS_TYPE + "$Recorder")),
                "subtypeCount", 2),
            // Events.java's one warning: the field calls of anonymous class 1 is only
            // incremented, and JDT reports such a field of a local or anonymous class as unused.
            "diagnostics", map("errorCount", 0, "warningCount", 1, "hasProblems", true));
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "analyze_method for detailed method analysis",
            "find_references for specific symbol usages",
            "get_call_hierarchy_incoming for method callers"));
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args, success(data, meta),
            sortedBy("qualifiedName", "data", "hierarchy", "subtypes"));
    }

    @Test
    @DisplayName("get_symbol_info inside anonymous class 1 names it Events$1")
    void symbolInfo() {
        Map<String, Object> data = map(
            "name", "Events$1",
            "kind", "class",
            "qualifiedName", ANONYMOUS_1,
            "typeKind", "class",
            "superclass", "Runnable",
            "modifiers", List.of(),
            "declaringType", EVENTS_TYPE,
            "filePath", EVENTS,
            "line", 31,
            "column", 15);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "find_references to find all usages",
            "go_to_definition to navigate to definition",
            "get_type_hierarchy for type inheritance"));
        assertPayload(mapper, new GetSymbolInfoTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("get_hover_info inside anonymous class 1 names it Events$1, in its name and its signature")
    void hoverInfo() {
        Map<String, Object> data = map(
            "name", "Events$1",
            "kind", "class",
            "signature", "class Events$1 extends Runnable",
            "modifiers", List.of(),
            "declaringType", EVENTS_TYPE,
            "filePath", EVENTS);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "go_to_definition to navigate to source",
            "find_references to find all usages",
            "get_javadoc for full documentation"));
        assertPayload(mapper, new GetHoverInfoTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("go_to_definition inside anonymous class 1 names it Events$1")
    void definition() {
        Map<String, Object> data = map(
            "symbol", "Events$1",
            "kind", "class",
            "location", map("filePath", EVENTS, "line", 31, "column", 15),
            "package", "com.example.unnamed");
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "find_references to see all usages of this symbol",
            "get_type_hierarchy if this is a type",
            "get_type_members to see all members of the containing type"));
        assertPayload(mapper, new GoToDefinitionTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("get_javadoc inside anonymous class 1 names it Events$1, which has no documentation")
    void javadoc() {
        Map<String, Object> data = map("symbol", "Events$1", "kind", "class", "hasDocumentation", false);
        Map<String, Object> meta = map("suggestedNextTools", List.of("get_hover_info for basic info"));
        assertPayload(mapper, new GetJavadocTool(() -> service), envelope,
            position(33, 0), success(data, meta));
    }

    @Test
    @DisplayName("find_affected_tests inside anonymous class 1 is INVALID_PARAMETER naming Events$1, which has no graph node")
    void affectedTests() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(33, 0),
            invalidParameter("Invalid parameter 'position': affected-test analysis supports project methods, "
                + "fields, and types; no graph node for 'Events$1'"));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> implementationsMeta(int count) {
        return map(
            "totalCount", count,
            "returnedCount", count,
            "truncated", false,
            "suggestedNextTools", List.of(
                "get_type_hierarchy for full inheritance chain",
                "find_references to see all usages",
                "get_type_members to see members of an implementation"));
    }
}
