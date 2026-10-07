package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.javalens.mcp.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.javalens.mcp.tools.unnamed.NestedTypes.FLOWS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract_variable, extract_constant and extract_method declare each generated variable,
 * constant and parameter with the type JDT's own refactorings declare it with
 * ({@code Bindings.normalizeForDeclarationUse}): an anonymous class as its direct supertype, a
 * captured {@code ? extends T} as T, a captured {@code ? super T} or {@code ?} as the bound of
 * the type variable it stands for, here Object, and a capture within type arguments as its
 * wildcard. The type each reports is the type its edits declare, and the edits applied compile.
 *
 * <p>Flows.java (nested-types): line 6 declares the constant {@code NUMBERS}, a
 * {@code List<? extends Number>}, ending at column 64, Flows' last static final field; line 13,
 * in the static method {@code first()}, reads {@code NUMBERS.get(0)} (columns 15 to 29) and
 * {@code NUMBERS.subList(0, 1)} (columns 43 to 64). {@code measure(texts, sink, any)} takes a
 * {@code List<? extends CharSequence>}, a {@code List<? super String>} and a {@code List<?>};
 * its statements on lines 21 to 23, each from column 8, read {@code texts.get(0)} (columns 28 to
 * 40), {@code sink.get(0)} (22 to 33) and {@code any.subList(0, 1)} (19 to 36). Line 28, the one
 * statement of {@code queue()}, returns {@code List.of} of an anonymous Runnable (columns 23 to
 * 63). Line 33 is the statement in the lambda that {@code shout()} passes to
 * {@code texts.forEach}, whose parameter {@code text} is a capture of
 * {@code ? extends CharSequence}; shout's closing brace ends at column 5 of line 35. Line 39 is
 * the same statement in {@code list()}'s loop over {@code var text}; list ends at column 5 of
 * line 41. Line 48, in {@code launch()}, calls {@code task.run()} on {@code var task}, an
 * anonymous Runnable; launch ends at column 5 of line 49. Every statement is indented 8 or 12
 * spaces and each member 4, which JDT's default formatter settings (the fixture has no others)
 * read as two or three and one indentation units and write as tabs. Every type named is in
 * java.lang or java.util.List, which Flows imports, so no import is added.
 */
class DeclarableTypeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the extraction",
        "get_diagnostics to verify no errors");

    private static final String PRINT_LINE = "System.out.println(text.length());";
    private static final String RUNNABLE = "new Runnable() { public void run() { } }";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String flows;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
        flows = helper.getFixturePath(NestedTypes.FIXTURE).resolve(FLOWS).toString();
    }

    // ---- extract_variable ----

    @Test
    @DisplayName("extract_variable of texts.get(0), a capture of ? extends CharSequence, declares a CharSequence")
    void variableOfUpperBoundedCapture() {
        assertVariable(21, 28, "texts.get(0)", "first", "CharSequence");
    }

    @Test
    @DisplayName("extract_variable of sink.get(0), a capture of ? super String, declares an Object, the bound of List's type variable")
    void variableOfLowerBoundedCapture() {
        assertVariable(22, 22, "sink.get(0)", "item", "Object");
    }

    @Test
    @DisplayName("extract_variable of any.subList(0, 1), a List of a capture of ?, declares a List<?>")
    void variableOfCaptureTypeArgument() {
        assertVariable(23, 19, "any.subList(0, 1)", "part", "List<?>");
    }

    @Test
    @DisplayName("extract_variable of an anonymous Runnable declares a Runnable, its single interface")
    void variableOfAnonymousClass() {
        assertVariable(28, 23, RUNNABLE, "job", "Runnable");
    }

    @Test
    @DisplayName("the four extract_variable edits applied together compile, and each variable has the reported type")
    void appliedVariablesCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(NestedTypes.FIXTURE);
        Path file = copyOf(FLOWS);
        ExtractVariableTool tool = new ExtractVariableTool(() -> copy);
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.addAll(edits(tool, variableArgs(file.toString(), 21, 28, "texts.get(0)", "first")));
        edits.addAll(edits(tool, variableArgs(file.toString(), 22, 22, "sink.get(0)", "item")));
        edits.addAll(edits(tool, variableArgs(file.toString(), 23, 19, "any.subList(0, 1)", "part")));
        edits.addAll(edits(tool, variableArgs(file.toString(), 28, 23, RUNNABLE, "job")));
        JdtServiceImpl reloaded = applyAndReload(file, edits);
        Map<String, String> locals = localTypes(reloaded.getCompilationUnit(file));
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, file), "errors in the extracted source"),
            () -> assertEquals("CharSequence", locals.get("first")),
            () -> assertEquals("Object", locals.get("item")),
            () -> assertEquals("List<?>", locals.get("part")),
            () -> assertEquals("Runnable", locals.get("job")));
    }

    // ---- extract_constant ----

    @Test
    @DisplayName("extract_constant of NUMBERS.get(0), a capture of ? extends Number, declares a Number")
    void constantOfUpperBoundedCapture() {
        assertConstant(15, "NUMBERS.get(0)", "HEAD", "Number");
    }

    @Test
    @DisplayName("extract_constant of NUMBERS.subList(0, 1), a List of a capture of ? extends Number, declares a List<? extends Number>")
    void constantOfCaptureTypeArgument() {
        assertConstant(43, "NUMBERS.subList(0, 1)", "PREFIX", "List<? extends Number>");
    }

    @Test
    @DisplayName("the two extract_constant edits applied together compile, and each constant is a field of the reported type")
    void appliedConstantsCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(NestedTypes.FIXTURE);
        Path file = copyOf(FLOWS);
        ExtractConstantTool tool = new ExtractConstantTool(() -> copy);
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.addAll(edits(tool, constantArgs(file.toString(), 15, "NUMBERS.get(0)", "HEAD")));
        edits.addAll(edits(tool, constantArgs(file.toString(), 43, "NUMBERS.subList(0, 1)", "PREFIX")));
        JdtServiceImpl reloaded = applyAndReload(file, edits);
        IType flowsType = reloaded.getCompilationUnit(file).getType("Flows");
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, file), "errors in the extracted source"),
            () -> assertEquals("Number", Signature.toString(flowsType.getField("HEAD").getTypeSignature())),
            () -> assertEquals("List<? extends Number>",
                Signature.toString(flowsType.getField("PREFIX").getTypeSignature())));
    }

    // ---- extract_method ----

    @Test
    @DisplayName("extract_method of a statement using the lambda parameter text, a capture of ? extends CharSequence, declares the parameter a CharSequence")
    void methodParameterOfLambdaCapture() {
        assertMethod(33, PRINT_LINE, "print", "text", "CharSequence", 35);
    }

    @Test
    @DisplayName("extract_method of a statement using the loop variable var text declares the parameter a CharSequence, never var")
    void methodParameterOfVarLoopVariable() {
        assertMethod(39, PRINT_LINE, "show", "text", "CharSequence", 41);
    }

    @Test
    @DisplayName("extract_method of task.run() on var task, an anonymous Runnable, declares the parameter a Runnable")
    void methodParameterOfAnonymousClass() {
        assertMethod(48, "task.run();", "start", "task", "Runnable", 49);
    }

    @Test
    @DisplayName("the three extract_method edits applied together compile, and each new method takes the reported parameter type")
    void appliedMethodsCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(NestedTypes.FIXTURE);
        Path file = copyOf(FLOWS);
        ExtractMethodTool tool = new ExtractMethodTool(() -> copy);
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.addAll(edits(tool, methodArgs(file.toString(), 33, PRINT_LINE, "print")));
        edits.addAll(edits(tool, methodArgs(file.toString(), 39, PRINT_LINE, "show")));
        edits.addAll(edits(tool, methodArgs(file.toString(), 48, "task.run();", "start")));
        JdtServiceImpl reloaded = applyAndReload(file, edits);
        IType flowsType = reloaded.getCompilationUnit(file).getType("Flows");
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, file), "errors in the extracted source"),
            () -> assertEquals(List.of("CharSequence"), parameterTypes(flowsType, "print")),
            () -> assertEquals(List.of("CharSequence"), parameterTypes(flowsType, "show")),
            () -> assertEquals(List.of("Runnable"), parameterTypes(flowsType, "start")));
    }

    // ---- expected payloads ----

    private void assertVariable(int line, int column, String expression, String name, String type) {
        String declaration = type + " " + name + " = " + expression + ";";
        Map<String, Object> data = map(
            "filePath", FLOWS,
            "variableName", name,
            "variableType", type,
            "expressionText", expression,
            "declaration", declaration,
            "edits", List.of(
                map("type", "insert", "line", line, "column", 8, "newText", declaration + "\n\t\t"),
                replace(line, column, expression, name)));
        assertPayload(mapper, new ExtractVariableTool(() -> service), envelope,
            variableArgs(flows, line, column, expression, name),
            success(data, map("suggestedNextTools", NEXT_TOOLS)), NestedTypes.withoutOffsets("data", "edits"));
    }

    private void assertConstant(int column, String expression, String name, String type) {
        String declaration = "private static final " + type + " " + name + " = " + expression + ";";
        Map<String, Object> data = map(
            "filePath", FLOWS,
            "constantName", name,
            "constantType", type,
            "expressionText", expression,
            "declaration", declaration,
            "containingType", "com.example.nested.Flows",
            "edits", List.of(
                map("type", "insert", "line", 6, "column", 64, "newText", "\n\t" + declaration),
                replace(13, column, expression, name)));
        assertPayload(mapper, new ExtractConstantTool(() -> service), envelope,
            constantArgs(flows, column, expression, name),
            success(data, map("suggestedNextTools", NEXT_TOOLS)), NestedTypes.withoutOffsets("data", "edits"));
    }

    private void assertMethod(int line, String statement, String name, String parameter, String type, int endLine) {
        String code = "private void " + name + "(" + type + " " + parameter + ") {\n\t\t" + statement + "\n\t}";
        String call = name + "(" + parameter + ");";
        Map<String, Object> data = map(
            "filePath", FLOWS,
            "methodName", name,
            "returnType", "void",
            "parameters", List.of(map("name", parameter, "type", type)),
            "newMethodCode", code,
            "methodCall", call,
            "edits", List.of(
                map("type", "insert", "line", endLine, "column", 5, "newText", "\n\n\t" + code),
                map("type", "replace", "startLine", line, "startColumn", startOf(line), "endLine", line,
                    "endColumn", startOf(line) + statement.length(), "oldText", statement, "newText", call)));
        UnaryOperator<JsonNode> edits = NestedTypes.withoutOffsets("data", "edits");
        assertPayload(mapper, new ExtractMethodTool(() -> service), envelope, methodArgs(flows, line, statement, name),
            success(data, map("suggestedNextTools", NEXT_TOOLS)), payload -> withUnixCode(edits.apply(payload)));
    }

    private static Map<String, Object> replace(int line, int column, String oldText, String newText) {
        return map("type", "replace", "startLine", line, "startColumn", column, "endLine", line,
            "endColumn", column + oldText.length(), "oldText", oldText, "newText", newText);
    }

    /** The column the extracted statement on {@code line} starts at: 12 in a lambda or loop body, else 8. */
    private static int startOf(int line) {
        return line == 48 ? 8 : 12;
    }

    /** {@code payload} with the line endings of its newMethodCode as "\n". */
    private static JsonNode withUnixCode(JsonNode payload) {
        if (payload.path("data") instanceof ObjectNode data && data.get("newMethodCode") instanceof TextNode code) {
            data.put("newMethodCode", code.asText().replace("\r\n", "\n"));
        }
        return payload;
    }

    // ---- arguments ----

    private ObjectNode variableArgs(String file, int line, int column, String expression, String name) {
        ObjectNode args = selection(file, line, column, line, column + expression.length());
        args.put("variableName", name);
        return args;
    }

    private ObjectNode constantArgs(String file, int column, String expression, String name) {
        ObjectNode args = selection(file, 13, column, 13, column + expression.length());
        args.put("constantName", name);
        return args;
    }

    private ObjectNode methodArgs(String file, int line, String statement, String name) {
        ObjectNode args = selection(file, line, startOf(line), line, startOf(line) + statement.length());
        args.put("methodName", name);
        return args;
    }

    private ObjectNode selection(String file, int startLine, int startColumn, int endLine, int endColumn) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file);
        args.put("startLine", startLine);
        args.put("startColumn", startColumn);
        args.put("endLine", endLine);
        args.put("endColumn", endColumn);
        return args;
    }

    // ---- applying edits ----

    private Path copyOf(String relative) {
        return helper.getTempDirectory().resolve(NestedTypes.FIXTURE).resolve(relative);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edits(Tool tool, ObjectNode args) {
        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> tool.getName() + " failed: " + response.getError());
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("edits");
    }

    private JdtServiceImpl applyAndReload(Path file, List<Map<String, Object>> edits) throws Exception {
        Files.writeString(file, NestedTypes.applied(Files.readString(file, StandardCharsets.UTF_8), edits),
            StandardCharsets.UTF_8);
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(helper.getTempDirectory().resolve(NestedTypes.FIXTURE));
        return reloaded;
    }

    /** Each local variable declared by a statement in {@code unit}, mapped to its declared type as written. */
    private static Map<String, String> localTypes(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        Map<String, String> types = new LinkedHashMap<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                for (Object fragment : node.fragments()) {
                    types.put(((VariableDeclarationFragment) fragment).getName().getIdentifier(), node.getType().toString());
                }
                return true;
            }
        });
        return types;
    }

    private static List<String> parameterTypes(IType type, String method) throws Exception {
        IMethod found = Arrays.stream(type.getMethods()).filter(m -> m.getElementName().equals(method))
            .findFirst().orElseThrow(() -> new AssertionError("no method " + method + " in " + type.getElementName()));
        return Arrays.stream(found.getParameterTypes()).map(Signature::toString).toList();
    }
}
