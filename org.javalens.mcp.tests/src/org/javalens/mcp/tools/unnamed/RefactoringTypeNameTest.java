package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.PullUpTool;
import org.javalens.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.NestedTypes.NESTED;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * pull_up and push_down name the types a member moves between fully qualified, with '$' between
 * nested types, an anonymous class by its number in its enclosing type and a local class after
 * its enclosing type.
 *
 * <p>Owner.java (nested-types): {@code second()} returns anonymous class 2, a Base subclass whose
 * one member {@code tune()} has its name at line 13, column 17, between line 12's opening brace
 * and line 15's closing one, each at column 8; {@code third()} declares the local class
 * {@code Twig extends Root}, an empty body from line 19 to the brace at line 20, indented 8.
 * Base.java ends with {@code hook()} and its closing brace on line 15; its members are separated
 * by one blank line. Root.java declares only {@code grow()} (name at line 4, column 9) after a
 * blank line; Leaf.java, {@code Leaf extends Root}, is an empty body closing on line 3. Each
 * file's edit is the one span where JDT's result differs from the source; JDT writes moved
 * members one tab per indentation level deeper than their class. The subclasses of a type come in
 * no defined order, so toTypes is compared sorted.
 */
class RefactoringTypeNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("pull_up of tune in anonymous class 2 names fromType com.example.nested.Owner$2 and toType com.example.nested.Base")
    void pullUp_fromAnonymousClass() {
        Map<String, Object> data = map(
            "memberName", "tune",
            "fromType", "com.example.nested.Owner$2",
            "toType", "com.example.nested.Base",
            "totalEdits", 2,
            "filesAffected", 2,
            "editsByFile", map(
                NESTED + "Base.java", List.of(edit(15, 0, 15, 0, "", "\n\tvoid tune() {\n\t}\n")),
                NESTED + "Owner.java", List.of(edit(13, 8, 15, 8, "    void tune() {\n            }\n        ", ""))));
        Map<String, Object> meta = map(
            "totalCount", 2,
            "returnedCount", 2,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the pull-up",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position("Owner.java", 13, 17),
            success(data, meta), NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("push_down of grow names toTypes com.example.nested.Leaf and the local class com.example.nested.Owner$Twig")
    void pushDown_intoLocalClass() {
        Map<String, Object> data = map(
            "memberName", "grow",
            "fromType", "com.example.nested.Root",
            "toTypes", List.of("com.example.nested.Leaf", "com.example.nested.Owner$Twig"),
            "totalEdits", 3,
            "filesAffected", 3,
            "editsByFile", map(
                NESTED + "Leaf.java", List.of(edit(3, 0, 3, 0, "", "\n\tvoid grow() {\n\t}\n")),
                NESTED + "Owner.java", List.of(edit(20, 0, 20, 0, "", "\n\t\t\tvoid grow() {\n\t\t\t}\n")),
                NESTED + "Root.java", List.of(edit(3, 0, 6, 0, "\n    void grow() {\n    }\n", ""))));
        Map<String, Object> meta = map(
            "totalCount", 3,
            "returnedCount", 3,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the push-down",
                "get_diagnostics to verify no errors"));
        assertPayload(mapper, new PushDownTool(() -> service), envelope, position("Root.java", 4, 9),
            success(data, meta), payload -> sortedToTypes(NestedTypes.unixFiles().apply(payload)));
    }

    private static Map<String, Object> edit(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(NestedTypes.FIXTURE).resolve(NESTED + file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    /** {@code payload} with data.toTypes sorted. */
    private static JsonNode sortedToTypes(JsonNode payload) {
        if (payload.path("data") instanceof ObjectNode data && data.get("toTypes") instanceof ArrayNode types) {
            List<JsonNode> names = new ArrayList<>();
            types.forEach(names::add);
            names.sort(Comparator.comparing(JsonNode::asText));
            ArrayNode sorted = data.putArray("toTypes");
            names.forEach(sorted::add);
        }
        return payload;
    }
}
