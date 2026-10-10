package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.PullUpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * pull_up lists JDT's warnings in the order of the places they name, by file and position, those
 * naming one place in the order JDT reports them, the same for every load of a project: JDT finds
 * the fields, methods and types a pulled-up member uses through hash sets of Java elements
 * (ReferenceFinderUtil.extractElements :173-180) and search results, whose order follows the hash
 * of the project's name, which every load makes anew.
 *
 * <p>The fixture is pull-up-access, package com.example.warn. Shape.java declares the empty class
 * {@code Shape} (its closing brace on line 3). Panel.java declares {@code Panel extends Shape} with
 * the private int fields alpha, beta, gamma, delta, epsilon and zeta (lines 4 to 9, each from
 * column 4) and {@code int total()} (line 11, name at column 8) returning their sum (line 12), its
 * closing brace on line 13 (ending at column 5) and Panel's on line 14. Pulling total() up into
 * Shape leaves each private field out of Shape's reach: JDT reports an error for each, its place
 * the field (PullUpRefactoringProcessor.checkAccessedFields :1045-1069), then, changing the
 * changed sources, makes each field package-private, as Shape needs it, with a warning, its place
 * the field too (MemberVisibilityAdjustor.adjustOutgoingVisibility :635-652, severity WARNING
 * :468). pull_up lists both kinds as warnings: for each field in declaration order, its error,
 * then its visibility warning. JDT orders the changed files by name
 * (TextChangeManager.getAllChanges :90-104): Panel.java, then Shape.java. Each file's edit is the
 * change reduced to its changed span: in Panel, from alpha's "private" to total()'s closing brace,
 * the fields without "private" and total() with the blank line before it gone; Shape gains, after
 * its opening brace's line, a blank line and total() one level deeper than Shape, a level being a
 * tab, its body's lines kept relative to the method's own indentation. Positions are zero-based;
 * raw offsets are not pinned and texts are compared with "\n" line endings.
 */
class PullUpWarningOrderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "pull-up-access";
    private static final String PANEL = "src/main/java/com/example/warn/Panel.java";
    private static final String SHAPE = "src/main/java/com/example/warn/Shape.java";
    private static final List<String> FIELDS = List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta");
    private static final String SUM = "return alpha + beta + gamma + delta + epsilon + zeta;";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Pulling Panel.total up into Shape warns for alpha to zeta in declaration order, at each of three loads")
    void warningsInDeclarationOrder() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(PANEL).toString());
        args.put("line", 11);
        args.put("column", 8);
        for (int load = 0; load < 3; load++) {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            assertPayload(mapper, new PullUpTool(() -> service), new EnvelopeHarness(service), args,
                success(data(), map(
                    "totalCount", 2,
                    "returnedCount", 2,
                    "suggestedNextTools", List.of(
                        "Apply the text edits to complete the pull-up",
                        "get_diagnostics to verify no errors"))),
                NestedTypes.unixFiles());
        }
    }

    private static Map<String, Object> data() {
        List<String> warnings = new ArrayList<>();
        StringBuilder privateFields = new StringBuilder();
        StringBuilder packageFields = new StringBuilder();
        for (String field : FIELDS) {
            warnings.add("Field 'com.example.warn.Panel." + field + "' referenced in one of the moved elements is "
                + "not accessible from type 'com.example.warn.Shape'");
            warnings.add("The visibility of field 'com.example.warn.Panel." + field + "' will be changed to package.");
            privateFields.append(privateFields.isEmpty() ? "" : "\n    ").append("private int ").append(field).append(';');
            packageFields.append(packageFields.isEmpty() ? "" : "\n    ").append("int ").append(field).append(';');
        }
        return map(
            "memberName", "total",
            "fromType", "com.example.warn.Panel",
            "toType", "com.example.warn.Shape",
            "totalEdits", 2,
            "filesAffected", 2,
            "editsByFile", map(
                PANEL, List.of(edit(4, 4, 13, 5,
                    privateFields + "\n\n    int total() {\n        " + SUM + "\n    }", packageFields.toString())),
                SHAPE, List.of(edit(3, 0, 3, 0, "", "\n\tint total() {\n\t    " + SUM + "\n\t}\n"))),
            "warnings", warnings);
    }

    /** A replacement of the text from start to end, its raw offsets not pinned. */
    private static Map<String, Object> edit(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText);
    }
}
