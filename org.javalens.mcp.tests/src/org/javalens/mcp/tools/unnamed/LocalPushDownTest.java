package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IMethod;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.MOSAIC;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.YARD;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * push_down moves a member into the direct subclasses of its class as the Java model holds them,
 * local and anonymous classes in and after lambda bodies included. A member whose class has an
 * anonymous direct subclass is refused, naming it, before anything else; one whose class has none
 * is refused. JDT's push down writes into the subclasses of its own type hierarchy, which leaves out
 * a class declared in a lambda body and reads one local class by the handle of another of the same
 * name: a member whose class has a direct subclass that hierarchy leaves out is refused, naming each
 * such subclass in name order. Where both agree, JDT's edits are returned, and they compile. The
 * fixture is lambda-hierarchy ({@link LambdaHierarchy}).
 */
class LocalPushDownTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("push_down of heft in Yard$Brick, whose one subclass is anonymous class 1 created in a lambda body, is INVALID_PARAMETER naming Yard$1")
    void anonymousSubclassInLambda() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(YARD, 8, 12),
            invalidParameter("Invalid parameter 'position': Member at position, heft in " + PACKAGE + "Yard$Brick, "
                + "cannot be pushed down into the anonymous subclass " + PACKAGE + "Yard$1, "
                + "which push down cannot add a member to"));
    }

    @Test
    @DisplayName("push_down of size in Host$Plate, extended by Thin in a lambda body, Thick and anonymous class 6, is INVALID_PARAMETER naming Host$6")
    void anonymousSubclassRefusedFirst() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(HOST, 12, 12),
            invalidParameter("Invalid parameter 'position': Member at position, size in " + PACKAGE + "Host$Plate, "
                + "cannot be pushed down into the anonymous subclass " + PACKAGE + "Host$6, "
                + "which push down cannot add a member to"));
    }

    @Test
    @DisplayName("push_down of tag in the interface Host$Mark, implemented only by anonymous classes, is INVALID_PARAMETER: no subclasses")
    void interfaceHasNoSubclasses() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(HOST, 8, 15),
            invalidParameter("Invalid parameter 'member': Declaring type has no subclasses to push into"));
    }

    @Test
    @DisplayName("push_down of depth in Yard$Slab, extended by Wet in a lambda body and Dry after it, is INVALID_PARAMETER naming Wet, which JDT's hierarchy leaves out")
    void localSubclassInLambda() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(YARD, 33, 12),
            invalidParameter("Invalid parameter 'position': Member at position, depth in " + PACKAGE + "Yard$Slab, "
                + "cannot be pushed down: JDT's push down reads the subclasses of " + PACKAGE + "Yard$Slab from its "
                + "own type hierarchy, which leaves out " + PACKAGE + "Yard$Wet"));
    }

    @Test
    @DisplayName("push_down of span in Mosaic$Tile, extended by the first of two local classes Echo, is INVALID_PARAMETER naming Echo, which JDT's hierarchy reads by the other's handle")
    void misnumberedLocalSubclass() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(MOSAIC, 5, 12),
            invalidParameter("Invalid parameter 'position': Member at position, span in " + PACKAGE + "Mosaic$Tile, "
                + "cannot be pushed down: JDT's push down reads the subclasses of " + PACKAGE + "Mosaic$Tile from its "
                + "own type hierarchy, which leaves out " + PACKAGE + "Mosaic$Echo"));
    }

    @Test
    @DisplayName("push_down of load in Yard$Beam moves it into the local classes Joist and Rafter, its direct subclasses")
    void localSubclasses() {
        // JDT removes load from Beam, leaving its closing brace, and writes load into each empty
        // body one level deeper than the local class, a level being a tab, the body's lines kept
        // relative to the method's own indentation. The one edit spans from the first difference
        // to the last: from the indentation Beam's closing brace shares with load's, to the end
        // of Rafter's opening line.
        String load = "\n\t\t\tint load() {\n\t\t\t    return 0;\n\t\t\t}";
        Map<String, Object> data = map(
            "memberName", "load",
            "fromType", PACKAGE + "Yard$Beam",
            "toTypes", List.of(PACKAGE + "Yard$Joist", PACKAGE + "Yard$Rafter"),
            "totalEdits", 1,
            "filesAffected", 1,
            "editsByFile", map(YARD, List.of(edit(19, 4, 27, 35,
                "    int load() {\n            return 0;\n        }\n    }\n\n    List<Beam> beams() {\n"
                    + "        class Joist extends Beam {\n        }\n        class Rafter extends Beam {",
                "}\n\n    List<Beam> beams() {\n        class Joist extends Beam {\n" + load + "\n        }\n"
                    + "        class Rafter extends Beam {\n" + load))));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the push-down",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(YARD, 19, 12),
            success(data, meta), NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("The push down of load into Joist and Rafter, applied, compiles: Beam declares no method, Joist and Rafter declare load")
    void appliedPushDownCompiles() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(LambdaHierarchy.FIXTURE);
        Path root = helper.getTempDirectory().resolve(LambdaHierarchy.FIXTURE);
        Path yard = root.resolve(YARD);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", yard.toString());
        args.put("line", 19);
        args.put("column", 12);
        ToolResponse response = new PushDownTool(() -> copy).execute(args);
        assertTrue(response.isSuccess(), () -> "push_down failed: " + response.getError());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = ((Map<String, List<Map<String, Object>>>)
            ((Map<String, Object>) response.getData()).get("editsByFile")).get(YARD);
        Files.writeString(yard, NestedTypes.applied(Files.readString(yard, StandardCharsets.UTF_8), edits),
            StandardCharsets.UTF_8);
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, yard), "errors in Yard.java"),
            () -> assertEquals(List.of(), methods(reloaded, "Yard.Beam")),
            () -> assertEquals(List.of("load"), methods(reloaded, "Yard$Joist")),
            () -> assertEquals(List.of("load"), methods(reloaded, "Yard$Rafter")));
    }

    private static List<String> methods(JdtServiceImpl service, String typeQualifiedName) throws Exception {
        return Arrays.stream(service.findType(PACKAGE + typeQualifiedName).getMethods())
            .map(IMethod::getElementName).toList();
    }

    private static Map<String, Object> edit(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(LambdaHierarchy.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
