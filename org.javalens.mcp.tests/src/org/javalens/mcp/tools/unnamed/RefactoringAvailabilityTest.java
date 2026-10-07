package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ExtractSuperclassTool;
import org.javalens.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.NestedTypes.NESTED;
import static org.javalens.mcp.tools.unnamed.NestedTypes.OUTER;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * extract_superclass and push_down run only where JDT's refactoring availability
 * (RefactoringAvailabilityTesterCore) allows them. A superclass is extracted only from a
 * top-level class or a static member class, so a member of an inner class or of a local class
 * is refused; a static member cannot be pushed down; and a constructor is available to neither.
 * Each refusal names the member or its class.
 *
 * <p>Outer.java (nested-types): the inner class {@code Inner} declares {@code descend()} (name at
 * line 7, column 20); the static member class {@code Nested} (line 12, its body's brace at column
 * 24) declares only {@code settle()} (name at line 13, column 20; closing brace ending at column
 * 9 of line 14); the local class {@code Step}, in {@code task()}, declares {@code advance()}
 * (line 25, column 24). Shelf.java: the constructor {@code Shelf()} (line 4, column 11).
 * Base.java: the static field {@code instances} (line 4, column 15), the constructor
 * {@code Base()} (line 6, column 4) and the static method {@code reset()} (line 9, column 16).
 */
class RefactoringAvailabilityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String TOP_LEVEL_OR_STATIC =
        "; a superclass can be extracted only from a top-level class or a static member class";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("extract_superclass of descend in the inner class Outer$Inner is INVALID_PARAMETER: the class is not static")
    void extractSuperclass_innerClass() {
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope,
            extractSuperclass(OUTER, 7, 20, "Deep"),
            invalidParameter("Invalid parameter 'position': Member at position is declared in the inner class "
                + "com.example.nested.Outer$Inner" + TOP_LEVEL_OR_STATIC));
    }

    @Test
    @DisplayName("extract_superclass of advance in the local class Outer$Step is INVALID_PARAMETER: the class is local")
    void extractSuperclass_localClass() {
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope,
            extractSuperclass(OUTER, 25, 24, "Moving"),
            invalidParameter("Invalid parameter 'position': Member at position is declared in the local class "
                + "com.example.nested.Outer$Step" + TOP_LEVEL_OR_STATIC));
    }

    @Test
    @DisplayName("extract_superclass of the constructor Shelf() is INVALID_PARAMETER naming it and its class")
    void extractSuperclass_constructor() {
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope,
            extractSuperclass(NESTED + "Shelf.java", 4, 11, "Rack"),
            invalidParameter("Invalid parameter 'position': Member at position, Shelf in com.example.nested.Shelf, "
                + "cannot be moved into a new superclass"));
    }

    @Test
    @DisplayName("extract_superclass of settle in the static member class Outer$Nested moves it into the new top-level superclass Settled, which Nested extends")
    void extractSuperclass_staticMemberClass() {
        // JDT writes the new class with a default constructor and the moved method under a tab
        // indent, the method's closing brace at its own indent; Outer.java's edit is the one
        // span from Nested's opening brace to the end of settle, which becomes the extends
        // clause and the brace (Nested keeps no other member).
        Map<String, Object> data = map(
            "superclassName", "Settled",
            "memberName", "settle",
            "fromType", "com.example.nested.Outer$Nested",
            "createdFiles", List.of(map(
                "filePath", NESTED + "Settled.java",
                "content", "package com.example.nested;\n\npublic class Settled {\n\n\tpublic Settled() {\n\t\tsuper();\n"
                    + "\t}\n\n\tpublic void settle() {\n\t}\n\n}")),
            "totalEdits", 1,
            "filesAffected", 1,
            "editsByFile", map(OUTER, List.of(map(
                "type", "replace", "startLine", 12, "startColumn", 24, "endLine", 14, "endColumn", 9,
                "oldText", "{\n        public void settle() {\n        }", "newText", "extends Settled {"))));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Create the new superclass file from createdFiles",
                "Apply the text edits to complete the extraction",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new ExtractSuperclassTool(() -> service), envelope,
            extractSuperclass(OUTER, 13, 20, "Settled"), success(data, meta), NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("push_down of the static field instances and the static method reset is INVALID_PARAMETER: a static member cannot be pushed down")
    void pushDown_staticMembers() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(NESTED + "Base.java", 4, 15),
            invalidParameter("Invalid parameter 'position': Member at position, instances in com.example.nested.Base, "
                + "is static; only an instance member can be pushed down"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(NESTED + "Base.java", 9, 16),
            invalidParameter("Invalid parameter 'position': Member at position, reset in com.example.nested.Base, "
                + "is static; only an instance member can be pushed down"));
    }

    @Test
    @DisplayName("push_down of the constructor Base() is INVALID_PARAMETER naming it and its class")
    void pushDown_constructor() {
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position(NESTED + "Base.java", 6, 4),
            invalidParameter("Invalid parameter 'position': Member at position, Base in com.example.nested.Base, "
                + "cannot be pushed down"));
    }

    private ObjectNode extractSuperclass(String file, int line, int column, String name) {
        ObjectNode args = position(file, line, column);
        args.put("superclassName", name);
        return args;
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(NestedTypes.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
