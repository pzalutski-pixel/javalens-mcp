package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * change_method_signature changes a method together with the methods it is renamed with by
 * rename_symbol: every method it overrides, every method that overrides it and every method a
 * subtype inherits together with it, repeated from each method so found. Each declaration keeps its
 * own parameter names, and the type it declares for a parameter whose type is not changed; each
 * call of any of them is rewritten. The declarations come first, in the order of their types by
 * file and position, then the calls. A method related to a method of a class file is refused,
 * naming that method. The changed sources compile. The fixture is lambda-hierarchy
 * ({@link LambdaHierarchy}).
 */
class LocalChangeSignatureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the signature change",
        "get_diagnostics to verify no errors");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Adding int turns to Loop.tie adds it to Knot.tie and Hitch.tie, which overrides both, and passes 1 at both calls")
    void parameterAddedAcrossOverridingChain() {
        Map<String, Object> data = data("tie", "tie", "void", "void", 0, List.of(parameter("turns", "int")), map(
            WEAVE, List.of(
                declaration(5, 8, 5, 18, "void tie()", "void tie(int turns)"),
                declaration(9, 15, 9, 25, "void tie()", "void tie(int turns)"),
                declaration(14, 15, 14, 25, "void tie()", "void tie(int turns)"),
                call(43, 8, 43, 18, "knot.tie()", "knot.tie(1)"),
                call(44, 8, 44, 18, "loop.tie()", "loop.tie(1)"))));
        assertChange(args(WEAVE, 9, 20, null, newParameter("turns", "int", "1")), data);
    }

    @Test
    @DisplayName("Adding int times to Splice.join adds it to Bend.join, which Splice.join implements in Sheet, each keeping its own parameter name, and passes 2 at both calls")
    void parameterAddedAcrossSubtypeImplementation() {
        Map<String, Object> data = data("join", "join", "void", "void", 1,
            List.of(parameter("cord", "String"), parameter("times", "int")), map(
                WEAVE, List.of(
                    declaration(19, 8, 19, 30, "void join(String rope)", "void join(String rope, int times)"),
                    declaration(23, 15, 23, 37, "void join(String cord)", "void join(String cord, int times)"),
                    call(48, 8, 48, 22, "bend.join(\"a\")", "bend.join(\"a\", 2)"),
                    call(49, 8, 49, 23, "sheet.join(\"b\")", "sheet.join(\"b\", 2)"))));
        assertChange(args(WEAVE, 23, 20, null, newParameter("cord", "String", null), newParameter("times", "int", "2")),
            data);
    }

    @Test
    @DisplayName("Adding String prefix to Mark.tag adds it to the tag of anonymous classes 1, 2 (in a lambda body), 3 and 5")
    void parameterAddedToAnonymousImplementors() {
        Map<String, Object> data = data("tag", "tag", "String", "String", 0, List.of(parameter("prefix", "String")), map(
            HOST, List.of(
                declaration(8, 8, 8, 20, "String tag()", "String tag(String prefix)"),
                declaration(19, 19, 19, 31, "String tag()", "String tag(String prefix)"),
                declaration(24, 19, 24, 31, "String tag()", "String tag(String prefix)"),
                declaration(29, 19, 29, 31, "String tag()", "String tag(String prefix)"),
                declaration(36, 19, 36, 31, "String tag()", "String tag(String prefix)"))));
        assertChange(args(HOST, 8, 15, null, newParameter("prefix", "String", "\"\"")), data);
    }

    @Test
    @DisplayName("Renaming Sink<T>.put(T) to deliver renames every put(String) overriding it, each keeping its own parameter type")
    void renameKeepsEachDeclaredType() {
        Map<String, Object> data = data("put", "deliver", "void", "void", 1, List.of(parameter("item", "T")), map(
            CHAIN, List.of(
                declaration(5, 8, 5, 24, "void put(T item)", "void deliver(T item)"),
                declaration(13, 15, 13, 36, "void put(String item)", "void deliver(String item)"),
                declaration(35, 15, 35, 36, "void put(String item)", "void deliver(String item)")),
            LOCALS, List.of(
                declaration(24, 15, 24, 36, "void put(String item)", "void deliver(String item)"),
                declaration(32, 19, 32, 40, "void put(String item)", "void deliver(String item)"),
                declaration(38, 19, 38, 40, "void put(String item)", "void deliver(String item)"),
                declaration(42, 19, 42, 40, "void put(String item)", "void deliver(String item)"))));
        assertChange(args(CHAIN, 5, 13, "deliver"), data);
    }

    @Test
    @DisplayName("Changing Ranked.compareTo, which implements the JDK's Comparable.compareTo, is INVALID_PARAMETER naming that binary method")
    void relatedBinaryMethod() {
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args(CHAIN, 48, 19, "rank"),
            invalidParameter("Invalid parameter 'position': Method at position, compareTo in " + PACKAGE + "Chain$Ranked, "
                + "cannot be changed: related method compareTo (declared in java.lang.Comparable) is binary"));
    }

    @Test
    @DisplayName("The changes of Loop.tie, Splice.join, Mark.tag and Sink.put, applied, compile")
    void appliedChangesCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(LambdaHierarchy.FIXTURE);
        Path root = helper.getTempDirectory().resolve(LambdaHierarchy.FIXTURE);
        ChangeMethodSignatureTool tool = new ChangeMethodSignatureTool(() -> copy);
        Map<String, List<Map<String, Object>>> edits = new LinkedHashMap<>();
        for (ObjectNode args : List.of(
                args(root, WEAVE, 9, 20, null, newParameter("turns", "int", "1")),
                args(root, WEAVE, 23, 20, null, newParameter("cord", "String", null), newParameter("times", "int", "2")),
                args(root, HOST, 8, 15, null, newParameter("prefix", "String", "\"\"")),
                args(root, CHAIN, 5, 13, "deliver"))) {
            editsByFile(tool, args).forEach((file, fileEdits) ->
                edits.computeIfAbsent(file, key -> new ArrayList<>()).addAll(fileEdits));
        }
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.writeString(path, applied(Files.readString(path, StandardCharsets.UTF_8), file.getValue()),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(Set.of(WEAVE, HOST, CHAIN, LOCALS), edits.keySet()),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(WEAVE)), "errors in Weave.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(HOST)), "errors in Host.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(CHAIN)), "errors in Chain.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(LOCALS)), "errors in Locals.java"));
    }

    private void assertChange(ObjectNode args, Map<String, Object> data) {
        int total = (int) data.get("totalEdits");
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    private static Map<String, Object> data(String oldName, String newName, String oldReturnType, String newReturnType,
                                            int oldParameterCount, List<Map<String, Object>> newParameters,
                                            Map<String, Object> editsByFile) {
        int total = editsByFile.values().stream().mapToInt(edits -> ((List<?>) edits).size()).sum();
        return map(
            "oldName", oldName,
            "newName", newName,
            "oldReturnType", oldReturnType,
            "newReturnType", newReturnType,
            "oldParameterCount", oldParameterCount,
            "newParameterCount", newParameters.size(),
            "newParameters", newParameters,
            "totalEdits", total,
            "filesAffected", editsByFile.size(),
            "editsByFile", editsByFile);
    }

    private static Map<String, Object> parameter(String name, String type) {
        return map("name", name, "type", type);
    }

    /** A declaration edit, its raw offsets not pinned. */
    private static Map<String, Object> declaration(int startLine, int startColumn, int endLine, int endColumn,
                                                   String oldSignature, String newSignature) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldSignature", oldSignature, "newSignature", newSignature, "isDeclaration", true);
    }

    /** A call edit, its raw offsets not pinned. */
    private static Map<String, Object> call(int startLine, int startColumn, int endLine, int endColumn,
                                            String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText, "isDeclaration", false);
    }

    private static Map<String, Object> newParameter(String name, String type, String defaultValue) {
        Map<String, Object> parameter = map("name", name, "type", type);
        if (defaultValue != null) {
            parameter.put("defaultValue", defaultValue);
        }
        return parameter;
    }

    @SafeVarargs
    private ObjectNode args(String file, int line, int column, String newName, Map<String, Object>... parameters) {
        return args(helper.getFixturePath(LambdaHierarchy.FIXTURE), file, line, column, newName, parameters);
    }

    @SafeVarargs
    private ObjectNode args(Path root, String file, int line, int column, String newName,
                            Map<String, Object>... parameters) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        if (newName != null) {
            args.put("newName", newName);
        }
        if (parameters.length > 0) {
            ArrayNode list = args.putArray("newParameters");
            for (Map<String, Object> parameter : parameters) {
                list.add(mapper.valueToTree(parameter));
            }
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> editsByFile(ChangeMethodSignatureTool tool, ObjectNode args) {
        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> "change_method_signature failed: " + response.getError());
        return (Map<String, List<Map<String, Object>>>) ((Map<String, Object>) response.getData()).get("editsByFile");
    }

    /** {@code source} with {@code edits} applied, the last in the source first; a declaration's text is its signature. */
    private static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Map<String, Object> edit) -> ((Number) edit.get("startOffset")).intValue())
            .reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            String newText = (String) (Boolean.TRUE.equals(edit.get("isDeclaration"))
                ? edit.get("newSignature") : edit.get("newText"));
            text.replace(((Number) edit.get("startOffset")).intValue(), ((Number) edit.get("endOffset")).intValue(), newText);
        }
        return text.toString();
    }
}
