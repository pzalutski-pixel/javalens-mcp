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

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * change_method_signature updates every place JDT's change-signature refactoring updates: calls,
 * the arguments of enum constants, Javadoc references, static imports, lambda expressions
 * implementing the method, and the implicit super() calls of a no-argument constructor, adding
 * super(...) to a constructor that calls it implicitly and a constructor to a subclass that declares
 * none. Each file's edits come in that order: declarations, then the references in source order,
 * then lambda expressions, then implicit super() calls, then static imports. An element of an
 * annotation type is refused. The changed sources compile.
 *
 * <p>The fixture is signature-occurrences, package com.example.sig. Calc.java's class Javadoc (line
 * 3) links {@code #twice(int)} (column 23) and {@code Calc#twice(int)} (column 47); Calc declares
 * {@code static int twice(int value)} (line 7, return type at column 18, name at 22),
 * {@code static int add(int value)} (line 11, return type at 18, name at 22) and
 * {@code static int add(int value, int step)}. Package com.example.use: Single.java imports
 * {@code com.example.sig.Calc.twice} statically (line 2, the name at column 14) and calls
 * {@code twice(2)} (line 6, column 15); Both.java imports {@code com.example.sig.Calc.add} statically
 * (line 2, ending at column 39) and calls {@code add(1)} (line 6, column 14) and {@code add(1, 2)}.
 * The compiler binds a static import of overloaded methods to the last of them, so the search for
 * {@code add(int)} reports the call in Both.java and not its import, which {@code add(1, 2)} still
 * needs. Level.java declares the enum {@code Level} with the constants {@code LOW(1)} (line 4,
 * column 4) and {@code HIGH(5)} (column 12, with a class body) and the constructor
 * {@code Level(int base)} (line 13, column 4). Base.java's Javadoc (line 3) links {@code #Base()}
 * (column 19); Base declares {@code public Base()} (line 7, name at column 11). Child.java declares
 * {@code public class Child extends Base} with no constructor, its body brace ending at line 2,
 * column 33. Other.java declares {@code Other extends Base} with {@code Other(int size)}, whose body
 * starts with {@code this.size = size;} (line 7, column 8), and {@code Other()}, which calls
 * {@code this(0)}. Maker.java creates {@code new Base()} (line 4, column 17) and an anonymous
 * {@code new Base() {}} (line 5, column 21, to line 6, column 5). Op.java declares the functional
 * interface {@code Op} with {@code int apply(int value)} (line 5, return type at column 4, name at
 * 8); Ops.java assigns the lambdas {@code value -> value + 1} (line 4, column 13) and
 * {@code (int value) -> value - 1} (line 5, column 13) and calls {@code inc.apply(1)} (line 8,
 * column 15) and {@code dec.apply(2)} (column 30). Flag.java declares the annotation type
 * {@code Flag} with the element {@code value()} (line 4, column 11). Positions are zero-based; an
 * edit's raw offsets, which depend on the checkout's line endings, are not pinned, and its texts
 * are compared with "\n" line endings. JDT's rewrite indents what it inserts with the project's
 * formatter settings, a tab for each level.
 */
class SignatureOccurrencesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "signature-occurrences";
    private static final String SIG = "src/main/java/com/example/sig/";
    private static final String USE = "src/main/java/com/example/use/";
    private static final String CALC = SIG + "Calc.java";
    private static final String LEVEL = SIG + "Level.java";
    private static final String BASE = SIG + "Base.java";
    private static final String CHILD = SIG + "Child.java";
    private static final String OTHER = SIG + "Other.java";
    private static final String MAKER = SIG + "Maker.java";
    private static final String OP = SIG + "Op.java";
    private static final String OPS = SIG + "Ops.java";
    private static final String FLAG = SIG + "Flag.java";
    private static final String SINGLE = USE + "Single.java";
    private static final String BOTH = USE + "Both.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the signature change",
        "get_diagnostics to verify no errors");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Renaming Calc.twice to doubled renames both Javadoc links, the call and the static import's name")
    void renameUpdatesJavadocAndStaticImport() {
        assertChange(args(CALC, 7, 22, "doubled"), data("twice", "doubled", "int", "int", 1,
            List.of(parameter("value", "int")), map(
                CALC, List.of(
                    declaration(7, 18, 7, 38, "int twice(int value)", "int doubled(int value)"),
                    replace(3, 23, 3, 34, "#twice(int)", "#doubled(int)"),
                    replace(3, 47, 3, 62, "Calc#twice(int)", "Calc#doubled(int)")),
                SINGLE, List.of(
                    replace(6, 15, 6, 23, "twice(2)", "doubled(2)"),
                    replace(2, 14, 2, 40, "com.example.sig.Calc.twice", "com.example.sig.Calc.doubled")))));
    }

    @Test
    @DisplayName("Renaming Calc.add(int) to plus adds a static import of plus after the import of add, which add(1, 2) still uses")
    void renameAddsStaticImportBesideOneStillUsed() {
        assertChange(args(CALC, 11, 22, "plus"), data("add", "plus", "int", "int", 1,
            List.of(parameter("value", "int")), map(
                CALC, List.of(
                    declaration(11, 18, 11, 36, "int add(int value)", "int plus(int value)")),
                BOTH, List.of(
                    replace(6, 14, 6, 20, "add(1)", "plus(1)"),
                    insert(2, 39, "\nimport static com.example.sig.Calc.plus;")))));
    }

    @Test
    @DisplayName("Adding String label to Level(int base) passes its default to the enum constants LOW(1) and HIGH(5), the body of HIGH untouched")
    void parameterAddedToEnumConstants() {
        assertChange(args(LEVEL, 13, 4, null, newParameter("base", "int", null), newParameter("label", "String", "\"x\"")),
            constructorData("Level", 1, List.of(parameter("base", "int"), parameter("label", "String")), map(
                LEVEL, List.of(
                    declaration(13, 4, 13, 19, "Level(int base)", "Level(int base, String label)"),
                    replace(4, 4, 4, 10, "LOW(1)", "LOW(1, \"x\")"),
                    replace(4, 12, 4, 19, "HIGH(5)", "HIGH(5, \"x\")")))));
    }

    @Test
    @DisplayName("Adding int seed to Base() updates its Javadoc link and both creations, adds super(7) to Other(int) and a constructor calling super(7) to Child")
    void parameterAddedToNoArgConstructor() {
        assertChange(args(BASE, 7, 11, null, newParameter("seed", "int", "7")),
            constructorData("Base", 0, List.of(parameter("seed", "int")), map(
                BASE, List.of(
                    declaration(7, 11, 7, 17, "Base()", "Base(int seed)"),
                    replace(3, 19, 3, 26, "#Base()", "#Base(int)")),
                MAKER, List.of(
                    replace(4, 17, 4, 27, "new Base()", "new Base(7)"),
                    replace(5, 21, 6, 5, "new Base() {\n    }", "new Base(7) {\n    }")),
                CHILD, List.of(
                    insert(2, 33, "\n\n\tpublic Child() {\n\t\tsuper(7);\n\t}")),
                OTHER, List.of(
                    insert(7, 8, "super(7);\n\t\t")))));
    }

    @Test
    @DisplayName("Adding int step to Op.apply adds it to both calls and to both lambda expressions, parenthesizing the one written without parentheses")
    void parameterAddedToLambdas() {
        assertChange(args(OP, 5, 8, null, newParameter("value", "int", null), newParameter("step", "int", "1")),
            data("apply", "apply", "int", "int", 1, List.of(parameter("value", "int"), parameter("step", "int")), map(
                OP, List.of(
                    declaration(5, 4, 5, 24, "int apply(int value)", "int apply(int value, int step)")),
                OPS, List.of(
                    replace(8, 15, 8, 27, "inc.apply(1)", "inc.apply(1, 1)"),
                    replace(8, 30, 8, 42, "dec.apply(2)", "dec.apply(2, 1)"),
                    replace(4, 13, 4, 18, "value", "(value, step)"),
                    replace(5, 13, 5, 24, "(int value)", "(int value, int step)")))));
    }

    @Test
    @DisplayName("Changing Flag.value, an element of an annotation type, is INVALID_PARAMETER pointing to rename_symbol")
    void annotationTypeElementRefused() {
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args(FLAG, 4, 11, "label"),
            invalidParameter("Invalid parameter 'position': Method at position, value in com.example.sig.Flag, "
                + "cannot be changed: it is an element of an annotation type; rename it with rename_symbol"));
    }

    @Test
    @DisplayName("The five changes, applied together, compile")
    void appliedChangesCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        ChangeMethodSignatureTool tool = new ChangeMethodSignatureTool(() -> copy);
        Map<String, List<Map<String, Object>>> edits = new LinkedHashMap<>();
        for (ObjectNode args : List.of(
                args(root, CALC, 7, 22, "doubled"),
                args(root, CALC, 11, 22, "plus"),
                args(root, LEVEL, 13, 4, null, newParameter("base", "int", null), newParameter("label", "String", "\"x\"")),
                args(root, BASE, 7, 11, null, newParameter("seed", "int", "7")),
                args(root, OP, 5, 8, null, newParameter("value", "int", null), newParameter("step", "int", "1")))) {
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
            () -> assertEquals(Set.of(CALC, SINGLE, BOTH, LEVEL, BASE, MAKER, CHILD, OTHER, OP, OPS), edits.keySet()),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(CALC)), "errors in Calc.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(SINGLE)), "errors in Single.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(BOTH)), "errors in Both.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(LEVEL)), "errors in Level.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(BASE)), "errors in Base.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(MAKER)), "errors in Maker.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(CHILD)), "errors in Child.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(OTHER)), "errors in Other.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(OP)), "errors in Op.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(OPS)), "errors in Ops.java"));
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
        Map<String, Object> data = map("oldName", oldName, "newName", newName, "oldReturnType", oldReturnType,
            "newReturnType", newReturnType);
        data.putAll(counts(oldParameterCount, newParameters, editsByFile));
        return data;
    }

    /** The data of a constructor's change, which reports no return types. */
    private static Map<String, Object> constructorData(String name, int oldParameterCount,
                                                       List<Map<String, Object>> newParameters,
                                                       Map<String, Object> editsByFile) {
        Map<String, Object> data = map("oldName", name, "newName", name);
        data.putAll(counts(oldParameterCount, newParameters, editsByFile));
        return data;
    }

    private static Map<String, Object> counts(int oldParameterCount, List<Map<String, Object>> newParameters,
                                              Map<String, Object> editsByFile) {
        int total = editsByFile.values().stream().mapToInt(edits -> ((List<?>) edits).size()).sum();
        return map(
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

    /** A replacement of the text from start to end by {@code newText}, its raw offsets not pinned. */
    private static Map<String, Object> replace(int startLine, int startColumn, int endLine, int endColumn,
                                               String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText, "isDeclaration", false);
    }

    /** An insertion of {@code newText} at line and column, its raw offset not pinned. */
    private static Map<String, Object> insert(int line, int column, String newText) {
        return map("type", "insert", "line", line, "column", column, "newText", newText, "isDeclaration", false);
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
        return args(helper.getFixturePath(FIXTURE), file, line, column, newName, parameters);
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

    /**
     * {@code source} with {@code edits} applied, the last in the source first: a declaration's text
     * is its signature, an insertion's is placed at its offset.
     */
    private static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(SignatureOccurrencesTest::start).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            if ("insert".equals(edit.get("type"))) {
                text.insert(start(edit), (String) edit.get("newText"));
                continue;
            }
            String newText = (String) (Boolean.TRUE.equals(edit.get("isDeclaration"))
                ? edit.get("newSignature") : edit.get("newText"));
            text.replace(start(edit), ((Number) edit.get("endOffset")).intValue(), newText);
        }
        return text.toString();
    }

    private static int start(Map<String, Object> edit) {
        return ((Number) ("insert".equals(edit.get("type")) ? edit.get("offset") : edit.get("startOffset"))).intValue();
    }
}
