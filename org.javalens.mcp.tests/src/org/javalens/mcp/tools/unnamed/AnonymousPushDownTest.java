package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.BOARD;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PANEL;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.SHAPES;

/**
 * push_down writes a member into each direct subclass of its class, and JDT's push down writes
 * into an anonymous class's enclosing declaration instead of the anonymous class, so a member
 * whose class has a direct anonymous subclass is refused, naming each such subclass by its
 * qualified name, in name order. An anonymous class further down the hierarchy is no destination
 * and refuses nothing. The fixture is Shapes.java, Panel.java and Board.java in unnamed-semantics
 * ({@link UnnamedSemantics}).
 */
class AnonymousPushDownTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedSemantics.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("push_down of sides in Shapes$Figure, extended by Square and anonymous classes 1 and 2, is INVALID_PARAMETER naming Shapes$1 and Shapes$2")
    void methodWithAnonymousSubclasses() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(SHAPES, 7, 12),
            invalidParameter("Invalid parameter 'position': Member at position, sides in " + PACKAGE + "Shapes$Figure, "
                + "cannot be pushed down into the anonymous subclasses " + PACKAGE + "Shapes$1, " + PACKAGE
                + "Shapes$2, which push down cannot add a member to"));
    }

    @Test
    @DisplayName("push_down of the field corners in Shapes$Figure is INVALID_PARAMETER naming Shapes$1 and Shapes$2")
    void fieldWithAnonymousSubclasses() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(SHAPES, 5, 12),
            invalidParameter("Invalid parameter 'position': Member at position, corners in " + PACKAGE
                + "Shapes$Figure, cannot be pushed down into the anonymous subclasses " + PACKAGE + "Shapes$1, "
                + PACKAGE + "Shapes$2, which push down cannot add a member to"));
    }

    @Test
    @DisplayName("push_down of weight in Shapes$Plain, extended only by anonymous class 3, is INVALID_PARAMETER naming Shapes$3")
    void methodWithOneAnonymousSubclass() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(SHAPES, 16, 12),
            invalidParameter("Invalid parameter 'position': Member at position, weight in " + PACKAGE + "Shapes$Plain, "
                + "cannot be pushed down into the anonymous subclass " + PACKAGE + "Shapes$3, "
                + "which push down cannot add a member to"));
    }

    @Test
    @DisplayName("push_down of widen in Panel moves it into Board, its one direct subclass; anonymous class 4 extends Board and is no destination")
    void anonymousSubclassBelowDirectSubclass() {
        // JDT writes the moved member one tab deeper than its class; each file's edit is the one
        // span where JDT's result differs from the source.
        Map<String, Object> data = map(
            "memberName", "widen",
            "fromType", PACKAGE + "Panel",
            "toTypes", List.of(PACKAGE + "Board"),
            "totalEdits", 2,
            "filesAffected", 2,
            "editsByFile", map(
                BOARD, List.of(edit(3, 0, 3, 0, "", "\n\tvoid widen() {\n\t}\n")),
                PANEL, List.of(edit(3, 0, 6, 0, "\n    void widen() {\n    }\n", ""))));
        Map<String, Object> meta = map(
            "totalCount", 2,
            "returnedCount", 2,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the push-down",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(PANEL, 4, 9),
            success(data, meta), NestedTypes.unixFiles());
    }

    private static Map<String, Object> edit(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(UnnamedSemantics.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
