package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.PushDownTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * push_down lists JDT's warnings in the order of the places they name, by file and position, those
 * naming one place in the order JDT reports them, the same for every load of a project: JDT
 * reports the visibility it changes by iterating the keys of a hash map of the members it adjusts
 * (MemberVisibilityAdjustor.rewriteVisibility :1087, the map PushDownRefactoringProcessor creates
 * at :1107 and hands over at MemberVisibilityAdjustor.setAdjustments :1134-1137), whose order
 * follows the hashes of Java elements, and so of the project's name, which every load makes anew.
 *
 * <p>The fixture is push-down-access, package com.example.down. Shape.java declares {@code Shape}
 * with the private int fields alpha, beta, gamma, delta, epsilon and zeta (lines 4 to 9, each from
 * column 4) and {@code int total()} (line 11, name at column 8) returning their sum (line 12), its
 * closing brace on line 13 (ending at column 5) and Shape's on line 14. Panel.java declares the empty
 * class {@code Panel extends Shape} (its closing brace on line 3). Pushing total() down into Panel
 * leaves each private field out of Panel's reach. JDT's accessibility check adds nothing: it passes
 * every field that exists (HierarchyProcessor.canBeAccessedFrom :481-485, which push down does not
 * override);
 * changing the sources, it makes each field protected, the threshold from a subclass to a field of
 * its supertype (MemberVisibilityAdjustor.thresholdTypeToField :1271-1274), with a warning, its
 * place the field (failure severity WARNING: PushDownRefactoringProcessor.copyMembers :944-956).
 * push_down lists the warnings in the fields' declaration order. JDT orders the changed files by name
 * (TextChangeManager.getAllChanges :90-104): Panel.java, then Shape.java. Each file's edit is the
 * change reduced to its changed span: Panel gains, after its opening brace's line, a blank line and
 * total() one level deeper than Panel, a level being a tab, its body's lines kept relative to the
 * method's own indentation; in Shape, from alpha's "private" after the "pr" it shares with
 * "protected" to total()'s closing brace, each field's "private" become "protected" and total()
 * with the blank line before it gone. Positions are zero-based; raw
 * offsets are not pinned and texts are compared with "\n" line endings.
 */
class PushDownWarningOrderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "push-down-access";
    private static final String PANEL = "src/main/java/com/example/down/Panel.java";
    private static final String SHAPE = "src/main/java/com/example/down/Shape.java";
    private static final List<String> FIELDS = List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta");
    private static final String SUM = "return alpha + beta + gamma + delta + epsilon + zeta;";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Pushing Shape.total down into Panel warns for alpha to zeta in declaration order, at each of three loads")
    void warningsInDeclarationOrder() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(SHAPE).toString());
        args.put("line", 11);
        args.put("column", 8);
        for (int load = 0; load < 3; load++) {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            assertPayload(mapper, new PushDownTool(() -> service), new EnvelopeHarness(service), args,
                success(data(), map(
                    "totalCount", 2,
                    "returnedCount", 2,
                    "suggestedNextTools", List.of(
                        "Apply the text edits to complete the push-down",
                        "get_diagnostics to verify no errors"))),
                NestedTypes.unixFiles());
        }
    }

    @Test
    @DisplayName("Pushing Shape.total down into Panel, its edits applied, compiles")
    @SuppressWarnings("unchecked")
    void appliedPushDownCompiles() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(SHAPE).toString());
        args.put("line", 11);
        args.put("column", 8);
        ToolResponse response = new PushDownTool(() -> copy).execute(args);
        assertTrue(response.isSuccess(), () -> "push_down failed: " + response.getError());
        Map<String, List<Map<String, Object>>> edits = (Map<String, List<Map<String, Object>>>)
            ((Map<String, Object>) response.getData()).get("editsByFile");
        for (Map.Entry<String, List<Map<String, Object>>> edited : edits.entrySet()) {
            Path path = root.resolve(edited.getKey());
            Files.writeString(path, NestedTypes.applied(Files.readString(path, StandardCharsets.UTF_8), edited.getValue()),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(PANEL)), "errors in " + PANEL),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(SHAPE)), "errors in " + SHAPE));
    }

    private static Map<String, Object> data() {
        List<String> warnings = new ArrayList<>();
        StringBuilder privateFields = new StringBuilder();
        StringBuilder protectedFields = new StringBuilder();
        for (String field : FIELDS) {
            warnings.add("The visibility of field 'com.example.down.Shape." + field + "' will be changed to protected.");
            privateFields.append(privateFields.isEmpty() ? "" : "\n    ").append("private int ").append(field).append(';');
            protectedFields.append(protectedFields.isEmpty() ? "" : "\n    ").append("protected int ").append(field).append(';');
        }
        return map(
            "memberName", "total",
            "fromType", "com.example.down.Shape",
            "toTypes", List.of("com.example.down.Panel"),
            "totalEdits", 2,
            "filesAffected", 2,
            "editsByFile", map(
                PANEL, List.of(edit(3, 0, 3, 0, "", "\n\tint total() {\n\t    " + SUM + "\n\t}\n")),
                SHAPE, List.of(edit(4, 6, 13, 5,
                    privateFields.substring(2) + "\n\n    int total() {\n        " + SUM + "\n    }",
                    protectedFields.substring(2)))),
            "warnings", warnings);
    }

    /** A replacement of the text from start to end, its raw offsets not pinned. */
    private static Map<String, Object> edit(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText);
    }
}
