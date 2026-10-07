package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.CALLS;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.OVERRIDES;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PACKAGE;

/**
 * analyze_method reports the method a method of an anonymous class overrides, as the compiler
 * binds it: the nearest declaration in the anonymous class's superclass chain, else in its
 * interfaces, whether the class is written directly in a method or in a lambda body, and whether
 * the overridden method is declared with a type parameter. A method that overrides nothing
 * reports no overridden method. The fixture is Overrides.java and Calls.java in
 * unnamed-semantics ({@link UnnamedSemantics}); none of these anonymous classes has a subclass,
 * so none is overridden.
 */
class AnonymousOverridesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String OVERRIDES_TYPE = PACKAGE + "Overrides";
    private static final List<String> ANALYZE_NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedSemantics.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("analyze_method of name in anonymous class 1, written in square(), reports it overrides Overrides$Shape's name")
    void anonymousClassInMethod() {
        Map<String, Object> data = map(
            "method", methodInfo("name", List.of("public"), OVERRIDES_TYPE + "$1", "name(): String", "String",
                OVERRIDES, 16),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map("overrides", map("method", "name", "qualifiedType", OVERRIDES_TYPE + "$Shape")));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(OVERRIDES, 16, 26),
            success(data, map("suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_method of size in anonymous class 2, written in a lambda body in later(), reports it overrides Overrides$Base's size")
    void anonymousClassInLambda() {
        Map<String, Object> data = map(
            "method", methodInfo("size", List.of(), OVERRIDES_TYPE + "$2", "size(): int", "int", OVERRIDES, 24),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map("overrides", map("method", "size", "qualifiedType", OVERRIDES_TYPE + "$Base")));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(OVERRIDES, 24, 16),
            success(data, map("suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_method of compareTo(String) in anonymous class 3, a Comparable<String>, reports it overrides java.lang.Comparable's compareTo, declared with T")
    void anonymousClassOfParameterizedInterface() {
        Map<String, Object> data = map(
            "method", methodInfo("compareTo", List.of("public"), OVERRIDES_TYPE + "$3",
                "compareTo(String other): int", "int", OVERRIDES, 32),
            "parameters", List.of(map("type", "String", "name", "other")),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map("overrides", map("method", "compareTo", "qualifiedType", "java.lang.Comparable")));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(OVERRIDES, 32, 23),
            success(data, map("suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_method of measure in anonymous class 1 of Calls, which Probe does not declare, reports no overridden method")
    void anonymousClassMethodOverridingNothing() {
        Map<String, Object> data = map(
            "method", methodInfo("measure", List.of(), PACKAGE + "Calls$1", "measure(): int", "int", CALLS, 36),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(CALLS, 36, 16),
            success(data, map("suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    private static Map<String, Object> methodInfo(String name, List<String> modifiers, String declaringType,
                                                  String signature, String returnType, String file, int line) {
        return map(
            "name", name,
            "constructor", false,
            "modifiers", modifiers,
            "declaringType", declaringType,
            "signature", signature,
            "returnType", returnType,
            "file", file,
            "line", line);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(UnnamedSemantics.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
