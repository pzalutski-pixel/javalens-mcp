package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.ExtractSuperclassTool;
import org.javalens.mcp.tools.PullUpTool;
import org.javalens.mcp.tools.PushDownTool;
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
import static org.javalens.mcp.tools.unnamed.UnnamedElements.without;

/**
 * The hierarchy refactorings refuse what they cannot change, as JDT's own availability rules
 * do. extract_interface resolves its position to the class there, or else the class enclosing
 * it, and refuses an anonymous class, which cannot declare an implements clause; a position in
 * an initializer is a position in its class. pull_up, push_down and extract_superclass resolve
 * their position to the member there and refuse an initializer, which is not a method or field,
 * and the anonymous class itself. push_down also refuses a member of an anonymous class, which
 * no class can extend, and extract_superclass one, as an anonymous class cannot declare a
 * superclass. pull_up moves a member of an anonymous class into its superclass, as JDT does.
 *
 * <p>Positions in Events.java: (13, 4) is in the static initializer; (33, 0) is the blank line
 * inside anonymous class 1, between its field {@code calls} and {@code run()}; (35, 16) is
 * {@code calls} in {@code calls++} within its {@code run()}, whose name is at (34, 24); (53, 17)
 * is the name of {@code accept} in anonymous class 3, the Sink of sink().
 */
class UnnamedRefactoringRefusalTest {

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
    @DisplayName("extract_interface inside anonymous class 1 is INVALID_PARAMETER: an anonymous class cannot declare an implements clause")
    void extractInterface_anonymousClass() {
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, extractInterface(33, 0),
            invalidParameter("Invalid parameter 'position': Class at position is the anonymous class " + ANONYMOUS_1
                + ", which cannot declare an implements clause"));
    }

    @Test
    @DisplayName("extract_interface in a method of anonymous class 1 is INVALID_PARAMETER naming the anonymous class")
    void extractInterface_memberOfAnonymousClass() {
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, extractInterface(35, 16),
            invalidParameter("Invalid parameter 'position': Class at position is the anonymous class " + ANONYMOUS_1
                + ", which cannot declare an implements clause"));
    }

    @Test
    @DisplayName("extract_interface in the static initializer extracts from Events, the class the initializer is in")
    void extractInterface_initializerIsInItsClass() {
        // Events' one public instance method is label() (line 126). The implements clause goes
        // right after the class name, which ends at column 19 of line 6. Raw offsets depend on
        // line endings, so they are removed; line and column pin the edit.
        Map<String, Object> data = map(
            "className", "Events",
            "interfaceName", "Labeled",
            "packageName", "com.example.unnamed",
            "interfaceFilePath", "src/main/java/com/example/unnamed/Labeled.java",
            "interfaceContent", "package com.example.unnamed;\n\npublic interface Labeled {\n\n    String label();\n\n}\n",
            "extractedMethods", List.of(map("name", "label", "signature", "String label()")),
            "classEdits", List.of(map("type", "insert", "line", 6, "column", 19, "newText", " implements Labeled")),
            "sourceFilePath", EVENTS);
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Create the interface file with the provided content",
                "Apply the classEdits to add implements clause",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, extractInterface(13, 4),
            success(data, meta), without("offset", "data", "classEdits"));
    }

    @Test
    @DisplayName("pull_up, push_down and extract_superclass in the static initializer are INVALID_PARAMETER: an initializer is not a method or field")
    void memberRefactorings_initializer() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(13, 4),
            invalidParameter("Invalid parameter 'position': Symbol at position is an initializer, "
                + "not a method or field to pull up"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(13, 4),
            invalidParameter("Invalid parameter 'position': Symbol at position is an initializer, "
                + "not a method or field to push down"));
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope, extractSuperclass(13, 4),
            invalidParameter("Invalid parameter 'position': Symbol at position is an initializer, "
                + "not a method or field to move into a new superclass"));
    }

    @Test
    @DisplayName("pull_up, push_down and extract_superclass inside anonymous class 1 are INVALID_PARAMETER naming the anonymous class, which is not a method or field")
    void memberRefactorings_anonymousClass() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(33, 0),
            invalidParameter("Invalid parameter 'position': Symbol at position is the anonymous class " + ANONYMOUS_1
                + ", not a method or field to pull up"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(33, 0),
            invalidParameter("Invalid parameter 'position': Symbol at position is the anonymous class " + ANONYMOUS_1
                + ", not a method or field to push down"));
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope, extractSuperclass(33, 0),
            invalidParameter("Invalid parameter 'position': Symbol at position is the anonymous class " + ANONYMOUS_1
                + ", not a method or field to move into a new superclass"));
    }

    @Test
    @DisplayName("pull_up of run in anonymous class 1 is not refused for its anonymous class: it reaches the superclass check, and Object is no project superclass")
    void pullUp_memberOfAnonymousClass() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position(34, 24),
            invalidParameter("Invalid parameter 'member': Declaring type has no project superclass to pull into"));
    }

    @Test
    @DisplayName("push_down of run in anonymous class 1 is INVALID_PARAMETER naming the anonymous class, which no class can extend")
    void pushDown_memberOfAnonymousClass() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(34, 24),
            invalidParameter("Invalid parameter 'position': Member at position is declared in the anonymous class "
                + ANONYMOUS_1 + ", which no class can extend"));
    }

    @Test
    @DisplayName("extract_superclass of run in anonymous class 1 is INVALID_PARAMETER naming the anonymous class, which cannot declare a superclass")
    void extractSuperclass_memberOfAnonymousClass() {
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope, extractSuperclass(34, 24),
            invalidParameter("Invalid parameter 'position': Member at position is declared in the anonymous class "
                + ANONYMOUS_1 + ", which cannot declare a superclass"));
    }

    @Test
    @DisplayName("extract_superclass of accept in anonymous class 3 is INVALID_PARAMETER naming anonymous class 3")
    void extractSuperclass_memberOfAnonymousSubclass() {
        // Anonymous class 3 extends the project class Sink; it still cannot declare a superclass.
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope, extractSuperclass(53, 17),
            invalidParameter("Invalid parameter 'position': Member at position is declared in the anonymous class "
                + ANONYMOUS_3 + ", which cannot declare a superclass"));
    }

    private ObjectNode extractInterface(int line, int column) {
        ObjectNode args = position(line, column);
        args.put("interfaceName", "Labeled");
        return args;
    }

    private ObjectNode extractSuperclass(int line, int column) {
        ObjectNode args = position(line, column);
        args.put("superclassName", "Base");
        return args;
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
