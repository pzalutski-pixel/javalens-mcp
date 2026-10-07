package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindImplementationsTool;
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
 * find_implementations lists, for a type, every subtype the Java model holds, and for a method
 * every method that overrides it as the compiler binds it: anonymous and local classes in and
 * after lambda bodies, under the model's numbering; an override of a method declared with a type
 * parameter; the body of an enum constant. An overload, a static method of the same name and a
 * type named only as a type argument are none. Each entry is located at its name; entries come by
 * file, then by where their type's declaration starts. The fixture is lambda-hierarchy
 * ({@link LambdaHierarchy}).
 */
class LocalImplementationsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "get_type_hierarchy for full inheritance chain",
        "find_references to see all usages",
        "get_type_members to see members of an implementation");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Mark's implementations are anonymous classes 1, 2 (in a lambda body), 3 and 5, each at the type named after new")
    void interfaceImplementorsInAndAfterLambda() {
        assertImplementations(position(HOST, 7, 14), "Mark", true, null, List.of(
            type("Host$1", "Host$1", HOST, 18, 25), type("Host$2", "Host$2", HOST, 23, 41),
            type("Host$3", "Host$3", HOST, 28, 25), type("Host$5", "Host$5", HOST, 35, 25)));
    }

    @Test
    @DisplayName("Mark.tag is implemented by tag in anonymous classes 1, 2 (in a lambda body), 3 and 5")
    void interfaceMethodImplementedInAndAfterLambda() {
        assertImplementations(position(HOST, 8, 15), "Mark", true, "tag", List.of(
            method("Host$1", "Host$1", "tag", HOST, 19, 26), method("Host$2", "Host$2", "tag", HOST, 24, 26),
            method("Host$3", "Host$3", "tag", HOST, 29, 26), method("Host$5", "Host$5", "tag", HOST, 36, 26)));
    }

    @Test
    @DisplayName("Plate.size is overridden in the local classes Thin (in a lambda body) and Thick, and in anonymous class 6")
    void classMethodOverriddenInAndAfterLambda() {
        assertImplementations(position(HOST, 12, 12), "Plate", false, "size", List.of(
            method("Thin", "Host$Thin", "size", HOST, 46, 20), method("Thick", "Host$Thick", "size", HOST, 53, 16),
            method("Host$6", "Host$6", "size", HOST, 58, 16)));
    }

    @Test
    @DisplayName("Sink<T>.put(T) is overridden by put(String) in Base, Leaf, Named, Local and anonymous classes 1 and 2 of Locals; Middle's put(String, int) is an overload")
    void genericMethodOverriddenWithTypeArgument() {
        assertImplementations(position(CHAIN, 5, 13), "Sink", true, "put", List.of(
            method("Base", "Chain$Base", "put", CHAIN, 13, 20), method("Leaf", "Chain$Leaf", "put", CHAIN, 35, 20),
            method("Named", "Locals$Named", "put", LOCALS, 24, 20), method("Local", "Locals$Local", "put", LOCALS, 32, 24),
            method("Locals$1", "Locals$1", "put", LOCALS, 38, 24), method("Locals$2", "Locals$2", "put", LOCALS, 42, 24)));
    }

    @Test
    @DisplayName("The static Base.count is overridden by nothing: Leaf's static count hides it")
    void staticMethodHasNoOverriders() {
        assertImplementations(position(CHAIN, 16, 19), "Base", false, "count", List.of());
    }

    @Test
    @DisplayName("Base's subclasses are Middle, Leaf, Named, Local, Deeper and anonymous class 1 of Locals; Ranked is none")
    void classSubtypesExcludeTypeArgumentMentions() {
        assertImplementations(position(CHAIN, 12, 17), "Base", false, null, List.of(
            type("Middle", "Chain$Middle", CHAIN, 25, 17), type("Leaf", "Chain$Leaf", CHAIN, 34, 17),
            type("Named", "Locals$Named", LOCALS, 23, 17), type("Local", "Locals$Local", LOCALS, 31, 14),
            type("Deeper", "Locals$Deeper", LOCALS, 35, 14), type("Locals$1", "Locals$1", LOCALS, 37, 42)));
    }

    @Test
    @DisplayName("Mode.shout is overridden in the body of the constant LOUD, Mode's anonymous class 1, of kind enum as the model flags it")
    void enumMethodOverriddenInConstantBody() {
        Map<String, Object> body = method("Locals$Mode$1", "Locals$Mode$1", "shout", LOCALS, 10, 19);
        body.put("kind", "enum");
        assertImplementations(position(LOCALS, 15, 15), "Mode", false, "shout", List.of(body));
    }

    private void assertImplementations(ObjectNode args, String symbol, boolean isInterface, String methodName,
                                       List<Map<String, Object>> implementations) {
        Map<String, Object> data = map("symbol", symbol, "isInterface", isInterface);
        if (methodName != null) {
            data.put("method", methodName);
        }
        data.put("totalImplementations", implementations.size());
        data.put("implementations", implementations);
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope, args, success(data, map(
            "totalCount", implementations.size(),
            "returnedCount", implementations.size(),
            "truncated", false,
            "suggestedNextTools", NEXT_TOOLS)));
    }

    /** A class implementing or extending the type, located at its name. */
    private static Map<String, Object> type(String name, String typeQualifiedName, String file, int line, int column) {
        return map("name", name, "qualifiedName", PACKAGE + typeQualifiedName, "kind", "class", "filePath", file,
            "line", line, "column", column);
    }

    /** A method overriding the method, in the class named, located at the method's name. */
    private static Map<String, Object> method(String name, String typeQualifiedName, String methodName, String file,
                                              int line, int column) {
        return map("name", name, "qualifiedName", PACKAGE + typeQualifiedName, "kind", "class", "method", methodName,
            "filePath", file, "line", line, "column", column);
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(LambdaHierarchy.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
