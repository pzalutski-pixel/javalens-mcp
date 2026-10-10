package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rename_symbol renames a type as JDT's rename-type refactoring does: its declaration, each of its
 * explicit constructors, and every reference, each edit replacing exactly the type's simple name,
 * also where the reference is qualified (an import, a static import naming one of its members, a
 * qualified name in code or in a Javadoc link); and a top-level type whose file is named after it
 * renames that file, reported in renamedFiles. A method reference is edited on the method's name,
 * in a static import as in a call, and an annotation element written as the single value of its
 * annotation is named before that value. Each rename, applied, compiles.
 *
 * <p>The fixture is type-rename. Widget.java (package com.example.shapes) declares {@code Widget}
 * (name at line 5, column 13) with the constant {@code KIND}, the constructors {@code Widget()} and
 * {@code Widget(String)} (names at lines 11 and 15, column 11), {@code static Widget of(String)}
 * (line 19: return type at column 18, name at 25) returning {@code new Widget(name)} (line 20,
 * column 19), and the static member class {@code Part} (line 28, column 24) holding a
 * {@code Widget} field (line 30, column 8). Its class Javadoc (line 3) links {@code #of(String)}
 * (name at column 30) and {@code com.example.shapes.Widget.Part} (Widget at column 83, Part at
 * 90); Part's Javadoc (line 27) links {@code Widget} (column 27). Gizmo.java declares
 * {@code Gizmo}; Tag.java declares the annotation {@code Tag} with {@code value()} (line 4, column
 * 11). Workshop.java (package com.example.use) imports Tag and Widget (Widget at line 3, column
 * 26) and statically imports {@code Widget.of} (line 6: Widget at 33, of at 40); its Javadoc
 * (line 9) links {@code com.example.shapes.Widget} (Widget at 38) and {@code Widget.Part} (Widget
 * at 61, Part at 68). It is annotated {@code @Tag("workshop")} (line 11, the value at column 5)
 * and declares, line by line from 14: {@code Widget first = of("first")} (4; of at 19);
 * {@code com.example.shapes.Widget second = new com.example.shapes.Widget("second")} (23, 62);
 * {@code Widget.Part part = new Widget.Part()} (Widget at 4 and 27, Part at 11 and 34);
 * {@code com.example.shapes.Widget.Part spare = new com.example.shapes.Widget.Part()} (Widget at
 * 23 and 66, Part at 30 and 73); {@code Function<String, Widget> maker = Widget::new} (21, 37);
 * {@code Bench bench = new Bench()} (4, 22); then {@code kind()}, annotated
 * {@code @Tag(value = "kind")} (line 21, value at 9), returning
 * {@code Widget.KIND + com.example.shapes.Widget.KIND + ...} (line 23: 15, 48). After it, the
 * package-private top-level class {@code Bench} (line 27, column 6) declares
 * {@code Widget held = Widget.of("held")} (line 29: Widget at 4 and 18, of at 25). Stool.java,
 * beside Workshop.java, declares {@code Stool}. Positions are
 * zero-based; each file's edits come last position first, and an edit's raw offsets are pinned
 * only as its span, the length of text it replaces.
 */
class TypeRenameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    static final String FIXTURE = "type-rename";
    static final String WIDGET = "src/main/java/com/example/shapes/Widget.java";
    static final String GADGET = "src/main/java/com/example/shapes/Gadget.java";
    static final String TAG = "src/main/java/com/example/shapes/Tag.java";
    static final String WORKSHOP = "src/main/java/com/example/use/Workshop.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the rename",
        "get_diagnostics to verify no errors after rename");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Renaming Widget to Gadget edits its declaration, both constructors and every reference on the simple name Widget, qualified ones included, and renames Widget.java to Gadget.java")
    void typeRenamesDeclarationConstructorsReferencesAndFile() {
        Map<String, Object> editsByFile = map(
            WIDGET, List.of(
                edit(30, 8, "Widget", "Gadget"), edit(27, 27, "Widget", "Gadget"), edit(20, 19, "Widget", "Gadget"),
                edit(19, 18, "Widget", "Gadget"), edit(15, 11, "Widget", "Gadget"), edit(11, 11, "Widget", "Gadget"),
                edit(5, 13, "Widget", "Gadget"), edit(3, 83, "Widget", "Gadget")),
            WORKSHOP, List.of(
                edit(29, 18, "Widget", "Gadget"), edit(29, 4, "Widget", "Gadget"), edit(23, 48, "Widget", "Gadget"),
                edit(23, 15, "Widget", "Gadget"), edit(18, 37, "Widget", "Gadget"), edit(18, 21, "Widget", "Gadget"),
                edit(17, 66, "Widget", "Gadget"), edit(17, 23, "Widget", "Gadget"), edit(16, 27, "Widget", "Gadget"),
                edit(16, 4, "Widget", "Gadget"), edit(15, 62, "Widget", "Gadget"), edit(15, 23, "Widget", "Gadget"),
                edit(14, 4, "Widget", "Gadget"), edit(9, 61, "Widget", "Gadget"), edit(9, 38, "Widget", "Gadget"),
                edit(6, 33, "Widget", "Gadget"), edit(3, 26, "Widget", "Gadget")));
        assertRename(position(WIDGET, 5, 13, "Gadget"), "Widget", "Gadget", "class", editsByFile,
            List.of(map("filePath", WIDGET, "newFilePath", GADGET)));
    }

    @Test
    @DisplayName("Renaming the member class Part to Piece edits its declaration and every reference on the simple name Part, qualified ones included, and renames no file")
    void memberTypeRenamesNoFile() {
        Map<String, Object> editsByFile = map(
            WIDGET, List.of(edit(28, 24, "Part", "Piece"), edit(3, 90, "Part", "Piece")),
            WORKSHOP, List.of(
                edit(17, 73, "Part", "Piece"), edit(17, 30, "Part", "Piece"), edit(16, 34, "Part", "Piece"),
                edit(16, 11, "Part", "Piece"), edit(9, 68, "Part", "Piece")));
        assertRename(position(WIDGET, 28, 24, "Piece"), "Part", "Piece", "class", editsByFile, null);
    }

    @Test
    @DisplayName("Renaming Bench, a top-level class in Workshop.java, edits its declaration and both references and renames no file")
    void secondaryTopLevelTypeRenamesNoFile() {
        Map<String, Object> editsByFile = map(
            WORKSHOP, List.of(edit(27, 6, "Bench", "Stand"), edit(19, 22, "Bench", "Stand"), edit(19, 4, "Bench", "Stand")));
        assertRename(position(WORKSHOP, 27, 6, "Stand"), "Bench", "Stand", "class", editsByFile, null);
    }

    @Test
    @DisplayName("Renaming Widget to Gizmo, whose file Gizmo.java already exists beside Widget.java, is INVALID_PARAMETER")
    void typeRenameOntoExistingFileIsRefused() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, position(WIDGET, 5, 13, "Gizmo"),
            invalidParameter("Invalid parameter 'newName': Compilation unit 'Gizmo.java' already exists"));
    }

    @Test
    @DisplayName("Renaming Bench, a top-level class in Workshop.java, to Stool, whose file Stool.java already exists beside Workshop.java, is INVALID_PARAMETER")
    void secondaryTypeRenameOntoExistingFileIsRefused() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, position(WORKSHOP, 27, 6, "Stool"),
            invalidParameter("Invalid parameter 'newName': Compilation unit 'Stool.java' already exists"));
    }

    @Test
    @DisplayName("Renaming Widget.of to create edits its declaration, its Javadoc link, both calls and the static import on the name of")
    void methodRenameEditsStaticImportOnTheName() {
        Map<String, Object> editsByFile = map(
            WIDGET, List.of(edit(19, 25, "of", "create"), edit(3, 30, "of", "create")),
            WORKSHOP, List.of(edit(29, 25, "of", "create"), edit(14, 19, "of", "create"), edit(6, 40, "of", "create")));
        assertRename(position(WIDGET, 19, 25, "create"), "of", "create", "method", editsByFile, null);
    }

    @Test
    @DisplayName("Renaming Tag.value to label edits its declaration and value = \"kind\", and names the single value of @Tag(\"workshop\") label = ")
    void annotationElementRenameNamesTheSingleValue() {
        Map<String, Object> editsByFile = map(
            TAG, List.of(edit(4, 11, "value", "label")),
            WORKSHOP, List.of(edit(21, 9, "value", "label"),
                map("line", 11, "column", 5, "endColumn", 5, "oldText", "", "newText", "label = ", "span", 0)));
        assertRename(position(TAG, 4, 11, "label"), "value", "label", "method", editsByFile, null);
    }

    @Test
    @DisplayName("Widget renamed to Gadget, its edits applied and Widget.java renamed to Gadget.java, compiles")
    void appliedTypeRenameCompiles() throws Exception {
        Path root = applied(WIDGET, 5, 13, "Gadget");
        Files.move(root.resolve(WIDGET), root.resolve(GADGET));
        assertCompiles(root, GADGET, WORKSHOP);
    }

    @Test
    @DisplayName("Bench renamed to Stand, its edits applied and no file renamed, compiles")
    void appliedSecondaryTypeRenameCompiles() throws Exception {
        assertCompiles(applied(WORKSHOP, 27, 6, "Stand"), WORKSHOP);
    }

    @Test
    @DisplayName("Part renamed to Piece, its edits applied, compiles")
    void appliedMemberTypeRenameCompiles() throws Exception {
        assertCompiles(applied(WIDGET, 28, 24, "Piece"), WIDGET, WORKSHOP);
    }

    @Test
    @DisplayName("Widget.of renamed to create, its edits applied, compiles")
    void appliedMethodRenameCompiles() throws Exception {
        assertCompiles(applied(WIDGET, 19, 25, "create"), WIDGET, WORKSHOP);
    }

    @Test
    @DisplayName("Tag.value renamed to label, its edits applied, compiles")
    void appliedAnnotationElementRenameCompiles() throws Exception {
        assertCompiles(applied(TAG, 4, 11, "label"), TAG, WORKSHOP);
    }

    /** The fixture copied, with the edits of renaming the symbol at the position applied to it. */
    @SuppressWarnings("unchecked")
    private Path applied(String file, int line, int column, String newName) throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        ToolResponse response = new RenameSymbolTool(() -> copy).execute(at(root, file, line, column, newName));
        assertTrue(response.isSuccess(), () -> "rename_symbol failed: " + response.getError());
        Map<String, List<Map<String, Object>>> edits = (Map<String, List<Map<String, Object>>>)
            ((Map<String, Object>) response.getData()).get("editsByFile");
        for (Map.Entry<String, List<Map<String, Object>>> edited : edits.entrySet()) {
            Path path = root.resolve(edited.getKey());
            Files.writeString(path, NestedTypes.applied(Files.readString(path, StandardCharsets.UTF_8), edited.getValue()),
                StandardCharsets.UTF_8);
        }
        return root;
    }

    /** {@code files} of the project at {@code root}, loaded afresh, have no compile error. */
    private static void assertCompiles(Path root, String... files) throws Exception {
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(List.of(files).stream().map(file -> (org.junit.jupiter.api.function.Executable) () ->
            assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(file)), "errors in " + file)));
    }

    private void assertRename(ObjectNode args, String oldName, String newName, String symbolKind,
                              Map<String, Object> editsByFile, List<Map<String, Object>> renamedFiles) {
        int total = editsByFile.values().stream().mapToInt(edits -> ((List<?>) edits).size()).sum();
        Map<String, Object> data = map(
            "oldName", oldName,
            "newName", newName,
            "symbolKind", symbolKind,
            "totalEdits", total,
            "filesAffected", editsByFile.size(),
            "editsByFile", editsByFile);
        if (renamedFiles != null) {
            data.put("renamedFiles", renamedFiles);
        }
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "suggestedNextTools", NEXT_TOOLS)),
            spans());
    }

    /** A rename edit replacing {@code oldName} at a zero-based line and column. */
    private static Map<String, Object> edit(int line, int column, String oldName, String newName) {
        return map("line", line, "column", column, "endColumn", column + oldName.length(), "oldText", oldName,
            "newText", newName, "span", oldName.length());
    }

    /** The payload with each edit's raw startOffset and endOffset replaced by the span between them. */
    private static UnaryOperator<JsonNode> spans() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            copy.path("data").path("editsByFile").forEach(edits -> edits.forEach(edit -> {
                ObjectNode object = (ObjectNode) edit;
                int span = object.path("endOffset").asInt() - object.path("startOffset").asInt();
                object.remove(List.of("startOffset", "endOffset"));
                object.put("span", span);
            }));
            return copy;
        };
    }

    private ObjectNode position(String file, int line, int column, String newName) {
        return at(helper.getFixturePath(FIXTURE), file, line, column, newName);
    }

    private ObjectNode at(Path root, String file, int line, int column, String newName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        return args;
    }
}
