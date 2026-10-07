package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IMethod;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.PullUpTool;
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
 * pull_up moves a member into the superclass of its class as the Java model holds it, for local
 * classes in and after lambda bodies too. A member whose class's superclass is java.lang.Object is
 * refused. JDT's pull up reads the superclass from its own type hierarchy, which leaves out a class
 * declared in a lambda body and reads one local class by the handle of another of the same name: a
 * member of a class whose superclass that hierarchy leaves out is refused, naming the superclass.
 * JDT's pull up also reads the subclasses of the superclass from an IMPLEMENTORS search, which
 * names a class declared in a lambda body by a handle that does not exist: a member is refused
 * when the superclass has such a subclass, naming it. Where both agree, JDT's edits are returned,
 * and they compile. The fixture is lambda-hierarchy ({@link LambdaHierarchy}).
 */
class LocalPullUpTest {

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
    @DisplayName("pull_up of wetness in Yard$Wet, a local class in a lambda body extending Slab, is INVALID_PARAMETER naming Slab, which JDT's hierarchy leaves out")
    void localClassInLambda() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(YARD, 41, 20),
            invalidParameter("Invalid parameter 'position': Member at position, wetness in " + PACKAGE + "Yard$Wet, "
                + "cannot be pulled up: JDT's pull up reads the superclass of " + PACKAGE + "Yard$Wet from its own type "
                + "hierarchy, which leaves out " + PACKAGE + "Yard$Slab"));
    }

    @Test
    @DisplayName("pull_up of glaze in the first local class Echo of Mosaic, extending Tile, is INVALID_PARAMETER naming Tile, which JDT's hierarchy gives the other Echo")
    void misnumberedLocalClass() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(MOSAIC, 18, 20),
            invalidParameter("Invalid parameter 'position': Member at position, glaze in " + PACKAGE + "Mosaic$Echo, "
                + "cannot be pulled up: JDT's pull up reads the superclass of " + PACKAGE + "Mosaic$Echo from its own "
                + "type hierarchy, which leaves out " + PACKAGE + "Mosaic$Tile"));
    }

    @Test
    @DisplayName("pull_up of grade in Yard$Dry into Slab, which Wet extends in a lambda body, is INVALID_PARAMETER naming Wet, which JDT's search of Slab's subclasses names by a handle that does not exist")
    void destinationWithSubclassInLambda() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(YARD, 48, 16),
            invalidParameter("Invalid parameter 'position': Member at position, grade in " + PACKAGE + "Yard$Dry, "
                + "cannot be pulled up: JDT's pull up reads the subclasses of " + PACKAGE + "Yard$Slab from a search "
                + "that names " + PACKAGE + "Yard$Wet by a handle the Java model does not hold"));
    }

    @Test
    @DisplayName("pull_up of tag in anonymous class 2, a Mark created in a lambda body, is INVALID_PARAMETER: its superclass is Object")
    void anonymousClassOfInterface() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(HOST, 24, 26),
            invalidParameter("Invalid parameter 'member': Declaring type has no project superclass to pull into"));
    }

    @Test
    @DisplayName("pull_up of rise in Yard$Pillar, a local class, moves it into Post")
    void localClass() {
        // JDT writes rise into Post's empty body after a blank line, one level deeper than Post, a
        // level being a tab, the body's lines kept relative to the method's own indentation; it
        // removes rise from Pillar, leaving Pillar's closing brace. The one edit spans from Post's
        // closing brace to the end of rise's closing brace.
        String posts = "    }\n\n    Object posts() {\n        class Pillar extends Post {";
        Map<String, Object> data = map(
            "memberName", "rise",
            "fromType", PACKAGE + "Yard$Pillar",
            "toType", PACKAGE + "Yard$Post",
            "totalEdits", 1,
            "filesAffected", 1,
            "editsByFile", map(YARD, List.of(edit(56, 0, 62, 13,
                posts + "\n            int rise() {\n                return 1;\n            }",
                "\n\t\tint rise() {\n\t\t    return 1;\n\t\t}\n" + posts))));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the pull-up",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(YARD, 60, 16),
            success(data, meta), NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("The pull up of rise into Post, applied, compiles: Post declares rise, Pillar declares no method")
    void appliedPullUpCompiles() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(LambdaHierarchy.FIXTURE);
        Path root = helper.getTempDirectory().resolve(LambdaHierarchy.FIXTURE);
        Path yard = root.resolve(YARD);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", yard.toString());
        args.put("line", 60);
        args.put("column", 16);
        ToolResponse response = new PullUpTool(() -> copy).execute(args);
        assertTrue(response.isSuccess(), () -> "pull_up failed: " + response.getError());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = ((Map<String, List<Map<String, Object>>>)
            ((Map<String, Object>) response.getData()).get("editsByFile")).get(YARD);
        Files.writeString(yard, NestedTypes.applied(Files.readString(yard, StandardCharsets.UTF_8), edits),
            StandardCharsets.UTF_8);
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, yard), "errors in Yard.java"),
            () -> assertEquals(List.of("rise"), methods(reloaded, "Yard.Post")),
            () -> assertEquals(List.of(), methods(reloaded, "Yard$Pillar")));
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
