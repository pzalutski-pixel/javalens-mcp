package org.javalens.mcp.tools.unnamed;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.CHAIN;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.LOCALS;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.WEAVE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rename_symbol renames a method together with every method it overrides, every method that
 * overrides it, and every method a subtype inherits together with it as one method, repeated from
 * each method so found, as the compiler binds them, wherever they are declared: in anonymous and
 * local classes created in or after a lambda body, and for a method declared with a type
 * parameter, whose overriders take a type argument in its place. A method of another name or
 * number of parameters is left alone. Each declaration and each call is renamed, and the renamed
 * sources compile. A method that is, or is renamed together with, a method of a class file is
 * refused, naming that method. The fixture is lambda-hierarchy ({@link LambdaHierarchy}); its
 * methods tag, size and put are never called, tie and join are.
 */
class LocalRenameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the rename",
        "get_diagnostics to verify no errors after rename");

    /** Mark.tag and the tag of anonymous classes 5, 3, 2 and 1, last in the file first. */
    private static final List<Map<String, Object>> TAG_EDITS = List.of(
        edit(36, 26, "tag", "label"), edit(29, 26, "tag", "label"), edit(24, 26, "tag", "label"),
        edit(19, 26, "tag", "label"), edit(8, 15, "tag", "label"));

    /** Plate.size and the size of anonymous class 6, Thick and Thin, last in the file first. */
    private static final List<Map<String, Object>> SIZE_EDITS = List.of(
        edit(58, 16, "size", "width"), edit(53, 16, "size", "width"), edit(46, 20, "size", "width"),
        edit(12, 12, "size", "width"));

    /** The calls loop.tie() and knot.tie(), then Hitch.tie, Loop.tie and Knot.tie. */
    private static final List<Map<String, Object>> TIE_EDITS = List.of(
        edit(44, 13, "tie", "bind"), edit(43, 13, "tie", "bind"), edit(14, 20, "tie", "bind"),
        edit(9, 20, "tie", "bind"), edit(5, 13, "tie", "bind"));

    /** The calls sheet.join("b") and bend.join("a"), then Splice.join and Bend.join. */
    private static final List<Map<String, Object>> JOIN_EDITS = List.of(
        edit(49, 14, "join", "weld"), edit(48, 13, "join", "weld"), edit(23, 20, "join", "weld"),
        edit(19, 13, "join", "weld"));

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Renaming Mark.tag renames the tag of anonymous classes 1, 2 (in a lambda body), 3 and 5 with it")
    void interfaceMethodWithAnonymousImplementors() {
        assertRename(position(HOST, 8, 15, "label"), "tag", "label", map(HOST, TAG_EDITS));
    }

    @Test
    @DisplayName("Renaming the tag of anonymous class 2, created in a lambda body, renames Mark.tag and every other tag with it")
    void anonymousImplementorInLambda() {
        assertRename(position(HOST, 24, 26, "label"), "tag", "label", map(HOST, TAG_EDITS));
    }

    @Test
    @DisplayName("Renaming Plate.size renames the size of Thin (in a lambda body), Thick and anonymous class 6 with it")
    void classMethodWithLocalAndAnonymousOverriders() {
        assertRename(position(HOST, 12, 12, "width"), "size", "width", map(HOST, SIZE_EDITS));
    }

    @Test
    @DisplayName("Renaming the size of Thin, a local class in a lambda body, renames Plate.size and every other size with it")
    void localOverriderInLambda() {
        assertRename(position(HOST, 46, 20, "width"), "size", "width", map(HOST, SIZE_EDITS));
    }

    @Test
    @DisplayName("Renaming Sink<T>.put(T) renames put(String) of Base, Leaf, Named, Local and anonymous classes 1 and 2 of Locals, never Middle's put(String, int)")
    void methodDeclaredWithTypeParameter() {
        assertRename(position(CHAIN, 5, 13, "deliver"), "put", "deliver", map(
            CHAIN, List.of(edit(35, 20, "put", "deliver"), edit(13, 20, "put", "deliver"), edit(5, 13, "put", "deliver")),
            LOCALS, List.of(edit(42, 24, "put", "deliver"), edit(38, 24, "put", "deliver"),
                edit(32, 24, "put", "deliver"), edit(24, 20, "put", "deliver"))));
    }

    @Test
    @DisplayName("Renaming the put of anonymous class 1 of Locals, a Local in a lambda body, renames up through Local, Base and Sink and down to every put(String)")
    void overriderOfLocalClassInLambda() {
        assertRename(position(LOCALS, 38, 24, "deliver"), "put", "deliver", map(
            CHAIN, List.of(edit(35, 20, "put", "deliver"), edit(13, 20, "put", "deliver"), edit(5, 13, "put", "deliver")),
            LOCALS, List.of(edit(42, 24, "put", "deliver"), edit(38, 24, "put", "deliver"),
                edit(32, 24, "put", "deliver"), edit(24, 20, "put", "deliver"))));
    }

    @Test
    @DisplayName("Renaming Loop.tie, of a class not implementing Knot, renames Hitch.tie, which overrides it and Knot.tie, and Knot.tie, with their calls")
    void methodOverriddenWithAnInterfaceMethod() {
        assertRename(position(WEAVE, 9, 20, "bind"), "tie", "bind", map(WEAVE, TIE_EDITS));
    }

    @Test
    @DisplayName("Renaming Hitch.tie, which overrides Loop.tie and Knot.tie, renames both, with their calls")
    void methodOverridingTwoUnrelatedMethods() {
        assertRename(position(WEAVE, 14, 20, "bind"), "tie", "bind", map(WEAVE, TIE_EDITS));
    }

    @Test
    @DisplayName("Renaming Splice.join renames Bend.join, which it implements in Sheet, a subclass declaring neither, with their calls")
    void classMethodImplementingInSubtype() {
        assertRename(position(WEAVE, 23, 20, "weld"), "join", "weld", map(WEAVE, JOIN_EDITS));
    }

    @Test
    @DisplayName("Renaming Bend.join renames Splice.join, which implements it in Sheet, with their calls")
    void interfaceMethodImplementedInSubtype() {
        assertRename(position(WEAVE, 19, 13, "weld"), "join", "weld", map(WEAVE, JOIN_EDITS));
    }

    @Test
    @DisplayName("Renaming Hook.hang(String) renames Peg<T>.hang(T), which implements it in Rack, a Peg<String>")
    void interfaceMethodImplementedWithTypeArgument() {
        assertRename(position(WEAVE, 31, 13, "drape"), "hang", "drape",
            map(WEAVE, List.of(edit(35, 20, "hang", "drape"), edit(31, 13, "hang", "drape"))));
    }

    @Test
    @DisplayName("Renaming Ranked.compareTo, which implements the JDK's Comparable.compareTo, is INVALID_PARAMETER naming that binary method")
    void relatedBinaryMethod() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, position(CHAIN, 48, 19, "rank"),
            invalidParameter("Invalid parameter 'position': Method at position, compareTo in " + PACKAGE + "Chain$Ranked, "
                + "cannot be renamed: related method compareTo (declared in java.lang.Comparable) is binary"));
    }

    @Test
    @DisplayName("Renaming List.of, a JDK method called in Host.marks, is INVALID_PARAMETER: it is binary")
    void binaryMethod() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, position(HOST, 40, 20, "listOf"),
            invalidParameter("Invalid parameter 'position': Method at position, of in java.util.List, "
                + "is binary and cannot be renamed"));
    }

    @Test
    @DisplayName("The renames of Mark.tag, Plate.size, Sink.put, Loop.tie and Splice.join, applied, compile")
    void appliedRenamesCompile() throws Exception {
        Path root = applyRenames(Set.of(HOST, CHAIN, LOCALS, WEAVE),
            new Rename(HOST, 8, 15, "label"),
            new Rename(HOST, 12, 12, "width"),
            new Rename(CHAIN, 5, 13, "deliver"),
            new Rename(WEAVE, 9, 20, "bind"),
            new Rename(WEAVE, 23, 20, "weld"));
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(HOST)), "errors in Host.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(CHAIN)), "errors in Chain.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(LOCALS)), "errors in Locals.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(WEAVE)), "errors in Weave.java"));
    }

    @Test
    @DisplayName("The renames of Knot.tie, Bend.join and Hook.hang, interface methods, applied, compile")
    void appliedInterfaceRenamesCompile() throws Exception {
        Path root = applyRenames(Set.of(WEAVE),
            new Rename(WEAVE, 5, 13, "bind"),
            new Rename(WEAVE, 19, 13, "weld"),
            new Rename(WEAVE, 31, 13, "drape"));
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(WEAVE)), "errors in Weave.java");
    }

    private record Rename(String file, int line, int column, String newName) {
    }

    /**
     * The fixture copied, with the edits of each rename, all computed on the unchanged copy,
     * applied to it, after checking that they edit exactly {@code files}.
     */
    private Path applyRenames(Set<String> files, Rename... renames) throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(LambdaHierarchy.FIXTURE);
        Path root = helper.getTempDirectory().resolve(LambdaHierarchy.FIXTURE);
        RenameSymbolTool tool = new RenameSymbolTool(() -> copy);
        Map<String, List<Map<String, Object>>> edits = new LinkedHashMap<>();
        for (Rename rename : renames) {
            editsByFile(tool, copyPosition(root, rename.file(), rename.line(), rename.column(), rename.newName()))
                .forEach((file, fileEdits) -> edits.computeIfAbsent(file, key -> new ArrayList<>()).addAll(fileEdits));
        }
        assertEquals(files, edits.keySet(), "the files the renames edit");
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.writeString(path, NestedTypes.applied(Files.readString(path, StandardCharsets.UTF_8), file.getValue()),
                StandardCharsets.UTF_8);
        }
        return root;
    }

    private void assertRename(ObjectNode args, String oldName, String newName, Map<String, Object> editsByFile) {
        int total = editsByFile.values().stream().mapToInt(edits -> ((List<?>) edits).size()).sum();
        Map<String, Object> data = map(
            "oldName", oldName,
            "newName", newName,
            "symbolKind", "method",
            "totalEdits", total,
            "filesAffected", editsByFile.size(),
            "editsByFile", editsByFile);
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    /** A rename edit of a name at a zero-based line and column; its raw offsets are not pinned. */
    private static Map<String, Object> edit(int line, int column, String oldName, String newName) {
        return map("line", line, "column", column, "endColumn", column + oldName.length(), "oldText", oldName,
            "newText", newName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> editsByFile(RenameSymbolTool tool, ObjectNode args) {
        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> "rename_symbol failed: " + response.getError());
        return (Map<String, List<Map<String, Object>>>) ((Map<String, Object>) response.getData()).get("editsByFile");
    }

    private ObjectNode position(String file, int line, int column, String newName) {
        return copyPosition(helper.getFixturePath(LambdaHierarchy.FIXTURE), file, line, column, newName);
    }

    private ObjectNode copyPosition(Path root, String file, int line, int column, String newName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        return args;
    }
}
