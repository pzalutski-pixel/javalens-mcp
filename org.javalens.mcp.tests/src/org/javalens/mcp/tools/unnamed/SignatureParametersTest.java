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
 * change_method_signature renames a parameter as JDT's change-signature refactoring does
 * (ChangeSignatureProcessor.AbstractDeclarationUpdate.changeParamgumentName :2306-2318): a
 * parameter given with oldName is the parameter of that name, renamed; each declaration and each
 * lambda expression that names it so takes the new name in its parameter list and in every
 * reference to it in its body, a declaration's @param tag naming it too (changeJavadocTags
 * :2648-2656); a Javadoc reference naming the parameter takes the new name
 * (DocReferenceUpdate.changeParamgumentName :2910-2918); calls keep their arguments. A declaration
 * or lambda expression that names the parameter otherwise keeps its own name. The edits of the
 * lambda expressions implementing any of the related methods come in source order, file by file,
 * as the other places do.
 *
 * <p>The fixture is signature-params, package com.example.params (zero-based positions). Scaler
 * declares {@code int apply(int factor)} (line 13, return type at column 4, name at 8), its
 * Javadoc's {@code @param factor} naming it at line 10, column 14. Doubler overrides it as
 * {@code int apply(int factor)} (line 5, return type at 11) and reads factor at line 6, column 20,
 * and at line 7, columns 23 and 32; Halver overrides it as {@code int apply(int amount)} (line 5,
 * return type at 11). Lambdas's class Javadoc links {@code Scaler#apply(int factor)} (line 2,
 * column 15); Lambdas assigns {@code factor -> factor * 3} (line 5, column 20, the body's factor at
 * 30) and {@code (int n) -> n * 4} (line 6, column 18), and calls {@code scaler.apply(5)} (line 9,
 * column 15) and {@code triple.apply(6)} (column 33). Ints and Nums each declare
 * {@code int calc(int value)} (line 3, return type at 4, Ints's name at 8); Both implements both
 * with {@code int calc(int value)} (line 3, return type at 11), so the three change together.
 * Mixed assigns {@code value -> value + 1} to an Ints (line 3, column 17), {@code value -> value + 2}
 * to a Nums (line 4, column 18) and {@code value -> value + 3} to an Ints (line 5, column 17).
 * Zero declares {@code int zero()} (line 3, name at column 8), which has no parameter.
 * Raw offsets are not pinned and texts are compared with "\n" line endings.
 */
class SignatureParametersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "signature-params";
    private static final String PARAMS = "src/main/java/com/example/params/";
    private static final String SCALER = PARAMS + "Scaler.java";
    private static final String DOUBLER = PARAMS + "Doubler.java";
    private static final String HALVER = PARAMS + "Halver.java";
    private static final String LAMBDAS = PARAMS + "Lambdas.java";
    private static final String INTS = PARAMS + "Ints.java";
    private static final String NUMS = PARAMS + "Nums.java";
    private static final String BOTH = PARAMS + "Both.java";
    private static final String MIXED = PARAMS + "Mixed.java";

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
    @DisplayName("Renaming Scaler.apply's parameter factor to number renames it where Scaler, Doubler and the first lambda name it, in their bodies, the @param tag and the Javadoc link, and leaves calls and Halver's amount")
    void renameParameter() {
        assertChange(scalerArgs(helper.getFixturePath(FIXTURE)), map(
            "oldName", "apply",
            "newName", "apply",
            "oldReturnType", "int",
            "newReturnType", "int",
            "oldParameterCount", 1,
            "newParameterCount", 1,
            "newParameters", List.of(map("name", "number", "type", "int")),
            "totalEdits", 13,
            "filesAffected", 4,
            "editsByFile", map(
                DOUBLER, List.of(
                    declaration(5, 11, 5, 32, "int apply(int factor)", "int apply(int number)"),
                    replace(6, 20, 6, 26, "factor", "number"),
                    replace(7, 23, 7, 29, "factor", "number"),
                    replace(7, 32, 7, 38, "factor", "number")),
                HALVER, List.of(
                    declaration(5, 11, 5, 32, "int apply(int amount)", "int apply(int amount)")),
                SCALER, List.of(
                    declaration(13, 4, 13, 25, "int apply(int factor)", "int apply(int number)"),
                    replace(10, 14, 10, 20, "factor", "number")),
                LAMBDAS, List.of(
                    replace(2, 15, 2, 39, "Scaler#apply(int factor)", "Scaler#apply(int number)"),
                    replace(9, 15, 9, 30, "scaler.apply(5)", "scaler.apply(5)"),
                    replace(9, 33, 9, 48, "triple.apply(6)", "triple.apply(6)"),
                    replace(5, 20, 5, 26, "factor", "number"),
                    replace(5, 30, 5, 36, "factor", "number"),
                    replace(6, 18, 6, 25, "(int n)", "(int n)")))));
    }

    @Test
    @DisplayName("The renamed parameter, applied, compiles")
    void renameParameterCompiles() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        Map<String, List<Map<String, Object>>> edits = editsByFile(new ChangeMethodSignatureTool(() -> copy),
            scalerArgs(root));
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.writeString(path, applied(Files.readString(path, StandardCharsets.UTF_8), file.getValue()),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        List<String> files = List.of(SCALER, DOUBLER, HALVER, LAMBDAS);
        assertEquals(Set.copyOf(files), edits.keySet());
        assertAll(files.stream().map(file -> (org.junit.jupiter.api.function.Executable) () ->
            assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(file)), "errors in " + file)));
    }

    @Test
    @DisplayName("A parameter whose oldName names no parameter of the method is refused, naming the method's parameters")
    void oldNameOfNoParameter() {
        ObjectNode args = args(helper.getFixturePath(FIXTURE), SCALER, 13, 8,
            map("name", "number", "oldName", "size", "type", "int"));
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'newParameters': oldName 'size' names no parameter of apply,"
                + " whose parameters are factor"));
    }

    @Test
    @DisplayName("A parameter with an oldName given to a method without parameters is refused, saying it has none")
    void oldNameOfMethodWithoutParameters() {
        ObjectNode args = args(helper.getFixturePath(FIXTURE), PARAMS + "Zero.java", 3, 8,
            map("name", "x", "oldName", "y", "type", "int"));
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'newParameters': oldName 'y' names no parameter of zero,"
                + " which has none"));
    }

    @Test
    @DisplayName("Adding int step to Ints.calc, with Nums.calc and Both.calc, edits Mixed's three lambda expressions in source order")
    void lambdasOfRelatedMethodsInSourceOrder() {
        assertChange(args(helper.getFixturePath(FIXTURE), INTS, 3, 8,
                map("name", "value", "type", "int"), map("name", "step", "type", "int", "defaultValue", "1")),
            map(
                "oldName", "calc",
                "newName", "calc",
                "oldReturnType", "int",
                "newReturnType", "int",
                "oldParameterCount", 1,
                "newParameterCount", 2,
                "newParameters", List.of(map("name", "value", "type", "int"), map("name", "step", "type", "int")),
                "totalEdits", 6,
                "filesAffected", 4,
                "editsByFile", map(
                    BOTH, List.of(declaration(3, 11, 3, 30, "int calc(int value)", "int calc(int value, int step)")),
                    INTS, List.of(declaration(3, 4, 3, 23, "int calc(int value)", "int calc(int value, int step)")),
                    NUMS, List.of(declaration(3, 4, 3, 23, "int calc(int value)", "int calc(int value, int step)")),
                    MIXED, List.of(
                        replace(3, 17, 3, 22, "value", "(value, step)"),
                        replace(4, 18, 4, 23, "value", "(value, step)"),
                        replace(5, 17, 5, 22, "value", "(value, step)")))));
    }

    private ObjectNode scalerArgs(Path root) {
        return args(root, SCALER, 13, 8, map("name", "number", "oldName", "factor", "type", "int"));
    }

    private void assertChange(ObjectNode args, Map<String, Object> data) {
        int total = (int) data.get("totalEdits");
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
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

    @SafeVarargs
    private ObjectNode args(Path root, String file, int line, int column, Map<String, Object>... parameters) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        ArrayNode list = args.putArray("newParameters");
        for (Map<String, Object> parameter : parameters) {
            list.add(mapper.valueToTree(parameter));
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
     * is its signature.
     */
    private static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Map<String, Object> edit) ->
            ((Number) edit.get("startOffset")).intValue()).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            String newText = (String) (Boolean.TRUE.equals(edit.get("isDeclaration"))
                ? edit.get("newSignature") : edit.get("newText"));
            text.replace(((Number) edit.get("startOffset")).intValue(), ((Number) edit.get("endOffset")).intValue(),
                newText);
        }
        return text.toString();
    }
}
