package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.CHAIN;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.LOCALS;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * get_super_method reports the method a method overrides as the compiler binds it: the nearest
 * declaration in the superclass chain, else in the interfaces, for a method of an anonymous or
 * local class declared in or after a lambda body, of an enum constant's body, and for a method
 * overriding one declared with a type parameter. A static method overrides nothing, whatever it
 * hides. The fixture is lambda-hierarchy ({@link LambdaHierarchy}).
 */
class LocalSuperMethodTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "find_implementations to find overriding methods",
        "get_type_hierarchy for inheritance chain");

    private static final Map<String, Object> MARK_TAG = method("tag", "Host$Mark", "tag(): String", List.of(), HOST, 8);
    private static final Map<String, Object> BASE_PUT =
        method("put", "Chain$Base", "put(String item): void", List.of("public"), CHAIN, 13);

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("tag of anonymous class 2, created in a lambda body, implements Mark.tag")
    void anonymousClassInLambda() {
        assertOverrides(position(HOST, 24, 26),
            method("tag", "Host$2", "tag(): String", List.of("public"), HOST, 24), MARK_TAG, true);
    }

    @Test
    @DisplayName("tag of anonymous class 5, created after anonymous class 4, implements Mark.tag")
    void anonymousClassAfterLambda() {
        assertOverrides(position(HOST, 36, 26),
            method("tag", "Host$5", "tag(): String", List.of("public"), HOST, 36), MARK_TAG, true);
    }

    @Test
    @DisplayName("size of the local class Thin, declared in a lambda body, overrides Plate.size")
    void localClassInLambda() {
        assertOverrides(position(HOST, 46, 20),
            method("size", "Host$Thin", "size(): int", List.of(), HOST, 46),
            method("size", "Host$Plate", "size(): int", List.of(), HOST, 12), false);
    }

    @Test
    @DisplayName("Base.put(String) implements Sink<T>.put(T), declared with a type parameter")
    void overrideOfMethodDeclaredWithTypeParameter() {
        assertOverrides(position(CHAIN, 13, 20), BASE_PUT,
            method("put", "Chain$Sink", "put(T item): void", List.of(), CHAIN, 5), true);
    }

    @Test
    @DisplayName("Leaf.put(String) overrides Base.put(String), the nearest, past Middle's overload put(String, int)")
    void nearestOverriddenPastOverload() {
        assertOverrides(position(CHAIN, 35, 20),
            method("put", "Chain$Leaf", "put(String item): void", List.of("public"), CHAIN, 35), BASE_PUT, false);
    }

    @Test
    @DisplayName("put of anonymous class 1 of Locals, a Local created in a lambda body, overrides the local class Local's put")
    void anonymousSubclassOfLocalClassInLambda() {
        assertOverrides(position(LOCALS, 38, 24),
            method("put", "Locals$1", "put(String item): void", List.of("public"), LOCALS, 38),
            method("put", "Locals$Local", "put(String item): void", List.of("public"), LOCALS, 32), false);
    }

    @Test
    @DisplayName("shout in the body of the constant LOUD overrides Mode.shout")
    void enumConstantBody() {
        assertOverrides(position(LOCALS, 10, 19),
            method("shout", "Locals$Mode$1", "shout(): String", List.of(), LOCALS, 10),
            method("shout", "Locals$Mode", "shout(): String", List.of(), LOCALS, 15), false);
    }

    @Test
    @DisplayName("At the parameter of the lambda quiet the method is sinks(), the nearest enclosing method with a name, which overrides nothing")
    void lambdaParameterBelongsToEnclosingMethod() {
        Map<String, Object> data = map(
            "method", method("sinks", "Locals", "sinks(): List<Sink<String>>", List.of(), LOCALS, 28),
            "overrides", null,
            "message", "This method does not override or implement any method");
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, position(LOCALS, 29, 35),
            success(data, map("suggestedNextTools", NEXT_TOOLS)));
    }

    @Test
    @DisplayName("The static Leaf.count overrides nothing: it hides Base.count")
    void staticMethodOverridesNothing() {
        Map<String, Object> data = map(
            "method", method("count", "Chain$Leaf", "count(): int", List.of("static"), CHAIN, 38),
            "overrides", null,
            "message", "This method does not override or implement any method");
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, position(CHAIN, 38, 19),
            success(data, map("suggestedNextTools", NEXT_TOOLS)));
    }

    private void assertOverrides(ObjectNode args, Map<String, Object> method, Map<String, Object> overrides,
                                 boolean implementsInterface) {
        Map<String, Object> data = map(
            "method", method,
            "overrides", overrides,
            "implementsInterface", implementsInterface);
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", NEXT_TOOLS)));
    }

    private static Map<String, Object> method(String name, String typeQualifiedName, String signature,
                                              List<String> modifiers, String file, int line) {
        return map("name", name, "declaringType", PACKAGE + typeQualifiedName, "signature", signature,
            "modifiers", modifiers, "filePath", file, "line", line);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(LambdaHierarchy.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
