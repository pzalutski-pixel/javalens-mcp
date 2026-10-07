package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS_TYPE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract_constant declares a constant holding an anonymous class with the class's direct
 * supertype, the one type a declaration can name: its superclass, or its single interface when
 * the superclass is Object, with its type arguments. The declaration it reports and the field
 * its edits insert carry the same type.
 *
 * <p>blanks() (line 136) returns four one-line anonymous classes, each on its own line from
 * column 12: a Runnable (line 138), an {@code Iterable<String>} (line 139), a
 * {@code ThreadLocal<String>} subclass (line 140) and an Object subclass (line 141). The
 * constant goes after Events' last static final field, RESET, whose declaration ends at column
 * 56 of line 10: one blank line, as between Events' two fields, then the declaration indented
 * one level like RESET, which JDT's default formatter settings (the fixture has no others)
 * write as a tab. Each type is in java.lang, so no import is added.
 */
class AnonymousConstantTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the extraction",
        "get_diagnostics to verify no errors");

    private static final String RUNNABLE = "new Runnable() { public void run() { } }";
    private static final String ITERABLE = "new Iterable<String>() { public java.util.Iterator<String> iterator() "
        + "{ return List.<String>of().iterator(); } }";
    private static final String THREAD_LOCAL = "new ThreadLocal<String>() { }";
    private static final String OBJECT = "new Object() { }";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String events;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedElements.FIXTURE);
        envelope = new EnvelopeHarness(service);
        events = helper.getFixturePath(UnnamedElements.FIXTURE).resolve(EVENTS).toString();
    }

    @Test
    @DisplayName("extract_constant of an anonymous Runnable declares the constant a Runnable, its single interface")
    void anonymousInterface() {
        assertExtraction(138, RUNNABLE, "BLANK_TASK", "Runnable");
    }

    @Test
    @DisplayName("extract_constant of an anonymous Iterable<String> declares the constant an Iterable<String>, its interface with its type argument")
    void anonymousParameterizedInterface() {
        assertExtraction(139, ITERABLE, "BLANK_NAMES", "Iterable<String>");
    }

    @Test
    @DisplayName("extract_constant of an anonymous ThreadLocal<String> subclass declares the constant a ThreadLocal<String>, its superclass with its type argument")
    void anonymousParameterizedSuperclass() {
        assertExtraction(140, THREAD_LOCAL, "BLANK_SLOT", "ThreadLocal<String>");
    }

    @Test
    @DisplayName("extract_constant of an anonymous Object subclass declares the constant an Object, its superclass")
    void anonymousObject() {
        assertExtraction(141, OBJECT, "BLANK_MARK", "Object");
    }

    @Test
    @DisplayName("the four extractions applied together compile, and each constant is a field of the reported type")
    void appliedExtractionsCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(UnnamedElements.FIXTURE);
        Path file = helper.getTempDirectory().resolve(UnnamedElements.FIXTURE).resolve(EVENTS);
        ExtractConstantTool tool = new ExtractConstantTool(() -> copy);
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.addAll(extractionEdits(tool, file, 138, RUNNABLE, "BLANK_TASK"));
        edits.addAll(extractionEdits(tool, file, 139, ITERABLE, "BLANK_NAMES"));
        edits.addAll(extractionEdits(tool, file, 140, THREAD_LOCAL, "BLANK_SLOT"));
        edits.addAll(extractionEdits(tool, file, 141, OBJECT, "BLANK_MARK"));
        Files.writeString(file, applied(Files.readString(file, StandardCharsets.UTF_8), edits), StandardCharsets.UTF_8);

        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(helper.getTempDirectory().resolve(UnnamedElements.FIXTURE));
        ICompilationUnit unit = reloaded.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        List<String> errors = Arrays.stream(ast.getProblems()).filter(IProblem::isError)
            .map(IProblem::getMessage).toList();
        IType type = unit.getType("Events");
        assertAll(
            () -> assertEquals(List.of(), errors, "errors in the extracted source"),
            () -> assertEquals("Runnable", fieldType(type, "BLANK_TASK")),
            () -> assertEquals("Iterable<String>", fieldType(type, "BLANK_NAMES")),
            () -> assertEquals("ThreadLocal<String>", fieldType(type, "BLANK_SLOT")),
            () -> assertEquals("Object", fieldType(type, "BLANK_MARK")));
    }

    private void assertExtraction(int line, String expression, String name, String type) {
        String declaration = "private static final " + type + " " + name + " = " + expression + ";";
        Map<String, Object> data = map(
            "filePath", EVENTS,
            "constantName", name,
            "constantType", type,
            "expressionText", expression,
            "declaration", declaration,
            "containingType", EVENTS_TYPE,
            "edits", List.of(
                map("type", "insert", "line", 10, "column", 56, "newText", "\n\n\t" + declaration),
                map("type", "replace", "startLine", line, "startColumn", 12, "endLine", line,
                    "endColumn", 12 + expression.length(), "oldText", expression, "newText", name)));
        assertPayload(mapper, new ExtractConstantTool(() -> service), envelope, selection(events, line, expression, name),
            success(data, map("suggestedNextTools", NEXT_TOOLS)), withoutOffsets());
    }

    private ObjectNode selection(String filePath, int line, String expression, String name) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("startLine", line);
        args.put("startColumn", 12);
        args.put("endLine", line);
        args.put("endColumn", 12 + expression.length());
        args.put("constantName", name);
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractionEdits(ExtractConstantTool tool, Path file, int line, String expression,
                                                      String name) {
        ToolResponse response = tool.execute(selection(file.toString(), line, expression, name));
        assertTrue(response.isSuccess(), () -> "extract_constant failed: " + response.getError());
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("edits");
    }

    /** {@code source} with {@code edits} applied, the last in the source first. */
    private static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(AnonymousConstantTest::start).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            String newText = (String) edit.get("newText");
            if ("insert".equals(edit.get("type"))) {
                text.insert(start(edit), newText);
            } else {
                text.replace(start(edit), ((Number) edit.get("endOffset")).intValue(), newText);
            }
        }
        return text.toString();
    }

    private static int start(Map<String, Object> edit) {
        return ((Number) edit.getOrDefault("offset", edit.get("startOffset"))).intValue();
    }

    private static String fieldType(IType type, String field) throws Exception {
        return Signature.toString(type.getField(field).getTypeSignature());
    }

    /**
     * {@code payload} without the raw offsets of its edits, which depend on the checkout's line
     * endings, and with each line ending of their text as "\n"; line and column pin each edit.
     */
    private static UnaryOperator<JsonNode> withoutOffsets() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            copy.path("data").path("edits").forEach(edit -> {
                if (edit instanceof ObjectNode object) {
                    object.remove(List.of("offset", "startOffset", "endOffset"));
                    if (object.get("newText") instanceof TextNode text) {
                        object.put("newText", text.asText().replace("\r\n", "\n"));
                    }
                }
            });
            return copy;
        };
    }
}
