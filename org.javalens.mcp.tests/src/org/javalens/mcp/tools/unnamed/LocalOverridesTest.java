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

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.CHAIN;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * analyze_method reports the method a method overrides and every method that overrides it, as the
 * compiler binds them, over the subtypes the Java model holds: anonymous and local classes in and
 * after lambda bodies, under the model's numbering, and overrides of a method declared with a type
 * parameter. Overriders come by file, then by where their class's declaration starts. A static
 * method overrides nothing. None of these methods is called or calls anything. The fixture is
 * lambda-hierarchy ({@link LambdaHierarchy}).
 */
class LocalOverridesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Mark.tag is overridden by tag in anonymous classes 1, 2 (in a lambda body), 3 and 5")
    void interfaceMethodOverriddenInAndAfterLambda() {
        assertAnalysis(position(HOST, 8, 15),
            methodInfo("tag", List.of(), "Host$Mark", "tag(): String", "String", HOST, 8), List.of(),
            map("overriddenBy", List.of(by("tag", "Host$1"), by("tag", "Host$2"), by("tag", "Host$3"),
                by("tag", "Host$5"))));
    }

    @Test
    @DisplayName("Plate.size is overridden in the local classes Thin (in a lambda body) and Thick, and in anonymous class 6")
    void classMethodOverriddenInAndAfterLambda() {
        assertAnalysis(position(HOST, 12, 12),
            methodInfo("size", List.of(), "Host$Plate", "size(): int", "int", HOST, 12), List.of(),
            map("overriddenBy", List.of(by("size", "Host$Thin"), by("size", "Host$Thick"), by("size", "Host$6"))));
    }

    @Test
    @DisplayName("Sink<T>.put(T) is overridden by put(String) in Base, Leaf, Named, Local and anonymous classes 1 and 2 of Locals")
    void genericMethodOverriddenWithTypeArgument() {
        assertAnalysis(position(CHAIN, 5, 13),
            methodInfo("put", List.of(), "Chain$Sink", "put(T item): void", "void", CHAIN, 5),
            List.of(map("type", "T", "name", "item")),
            map("overriddenBy", List.of(by("put", "Chain$Base"), by("put", "Chain$Leaf"), by("put", "Locals$Named"),
                by("put", "Locals$Local"), by("put", "Locals$1"), by("put", "Locals$2"))));
    }

    @Test
    @DisplayName("Base.put(String) overrides Sink.put and is overridden in Leaf, Named, Local and anonymous class 1 of Locals")
    void overridesAndOverriddenBy() {
        assertAnalysis(position(CHAIN, 13, 20),
            methodInfo("put", List.of("public"), "Chain$Base", "put(String item): void", "void", CHAIN, 13),
            List.of(map("type", "String", "name", "item")),
            map("overrides", by("put", "Chain$Sink"),
                "overriddenBy", List.of(by("put", "Chain$Leaf"), by("put", "Locals$Named"), by("put", "Locals$Local"),
                    by("put", "Locals$1"))));
    }

    @Test
    @DisplayName("size of the local class Thin, declared in a lambda body, overrides Plate.size and is overridden by nothing")
    void localClassInLambdaOverrides() {
        assertAnalysis(position(HOST, 46, 20),
            methodInfo("size", List.of(), "Host$Thin", "size(): int", "int", HOST, 46), List.of(),
            map("overrides", by("size", "Host$Plate")));
    }

    @Test
    @DisplayName("The static Leaf.count overrides nothing and is overridden by nothing")
    void staticMethodHasNoOverrideInformation() {
        assertAnalysis(position(CHAIN, 38, 19),
            methodInfo("count", List.of("static"), "Chain$Leaf", "count(): int", "int", CHAIN, 38), List.of(),
            map());
    }

    private void assertAnalysis(ObjectNode args, Map<String, Object> method, List<Map<String, Object>> parameters,
                                Map<String, Object> overrides) {
        Map<String, Object> data = map(
            "method", method,
            "parameters", parameters,
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", overrides);
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", NEXT_TOOLS)));
    }

    private static Map<String, Object> methodInfo(String name, List<String> modifiers, String typeQualifiedName,
                                                  String signature, String returnType, String file, int line) {
        return map(
            "name", name,
            "constructor", false,
            "modifiers", modifiers,
            "declaringType", PACKAGE + typeQualifiedName,
            "signature", signature,
            "returnType", returnType,
            "file", file,
            "line", line);
    }

    /** A method named with the qualified name of its declaring class. */
    private static Map<String, Object> by(String method, String typeQualifiedName) {
        return map("method", method, "qualifiedType", PACKAGE + typeQualifiedName);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(LambdaHierarchy.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
