package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.CHAIN;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.LOCALS;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.OBJECT;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.anonymous;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.type;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * get_type_hierarchy reports every supertype and every subtype of a type as the Java model holds
 * them: anonymous and local classes declared in lambda bodies and after them are listed under the
 * model's numbering, a local or anonymous class reports its own supertypes, and a type named only
 * as a type argument of a supertype, or a lambda expression, is no subtype. Superclasses run from
 * the nearest; interfaces in declaration order, each followed by its own superinterfaces, then
 * those of the superclass; subtypes by file, then by where their declaration starts. The fixture
 * is lambda-hierarchy ({@link LambdaHierarchy}).
 */
class LocalTypeHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "get_type_members to see members of a specific type",
        "find_implementations to find all implementors",
        "find_references to see where type is used");

    private static final Map<String, Object> MARK = type("Mark", "Host$Mark", "interface", HOST, 7);
    private static final Map<String, Object> PLATE = type("Plate", "Host$Plate", "class", HOST, 11);
    private static final Map<String, Object> SINK = type("Sink", "Chain$Sink", "interface", CHAIN, 4);
    private static final Map<String, Object> PIPE = type("Pipe", "Chain$Pipe", "interface", CHAIN, 8);
    private static final Map<String, Object> BASE = type("Base", "Chain$Base", "class", CHAIN, 12);
    private static final Map<String, Object> MIDDLE = type("Middle", "Chain$Middle", "class", CHAIN, 25);
    private static final Map<String, Object> LEAF = type("Leaf", "Chain$Leaf", "class", CHAIN, 34);
    private static final Map<String, Object> NAMED = type("Named", "Locals$Named", "class", LOCALS, 23);
    private static final Map<String, Object> LOCAL = type("Local", "Locals$Local", "class", LOCALS, 31);
    private static final Map<String, Object> DEEPER = type("Deeper", "Locals$Deeper", "class", LOCALS, 35);

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Mark's subtypes are anonymous classes 1, 2 (in a lambda body), 3 and 5, never anonymous class 4, an Object")
    void interfaceWithAnonymousImplementorsInAndAfterLambda() {
        assertHierarchy(position(HOST, 7, 14), MARK, List.of(), List.of(), List.of(
            anonymous("Host$1", HOST, 18), anonymous("Host$2", HOST, 23),
            anonymous("Host$3", HOST, 28), anonymous("Host$5", HOST, 35)));
    }

    @Test
    @DisplayName("Plate's subtypes are the local class Thin (in a lambda body), the local class Thick and anonymous class 6")
    void classWithLocalSubclassesInAndAfterLambda() {
        assertHierarchy(position(HOST, 11, 17), PLATE, List.of(OBJECT), List.of(), List.of(
            type("Thin", "Host$Thin", "class", HOST, 45), type("Thick", "Host$Thick", "class", HOST, 52),
            anonymous("Host$6", HOST, 57)));
    }

    @Test
    @DisplayName("Anonymous class 2, created in a lambda body, extends Object and implements Mark")
    void anonymousClassInLambdaReportsItsSupertypes() {
        assertHierarchy(position(HOST, 27, 8), anonymous("Host$2", HOST, 23), List.of(OBJECT), List.of(MARK),
            List.of());
    }

    @Test
    @DisplayName("The local class Thin, declared in a lambda body, extends Plate, then Object")
    void localClassInLambdaReportsItsSuperclasses() {
        assertHierarchy(position(HOST, 45, 18), type("Thin", "Host$Thin", "class", HOST, 45),
            List.of(PLATE, OBJECT), List.of(), List.of());
    }

    @Test
    @DisplayName("Sink<T>'s subtypes: Pipe, the classes implementing it directly or through Pipe or Base, named, local and anonymous; never Ranked or the lambda quiet")
    void genericInterfaceSubtypesAcrossFiles() {
        assertHierarchy(byName("com.example.hierarchy.Chain.Sink"), SINK, List.of(), List.of(), List.of(
            PIPE, BASE, MIDDLE, LEAF, NAMED, LOCAL, DEEPER,
            anonymous("Locals$1", LOCALS, 37), anonymous("Locals$2", LOCALS, 41)));
    }

    @Test
    @DisplayName("Leaf's superclasses run Middle, Base, Object; its interfaces are Pipe, then Pipe's Sink, reached through Middle")
    void grandparentChainAndInheritedInterfaces() {
        assertHierarchy(byName("com.example.hierarchy.Chain.Leaf"), LEAF, List.of(MIDDLE, BASE, OBJECT),
            List.of(PIPE, SINK), List.of());
    }

    @Test
    @DisplayName("Base's subtypes are Middle, Leaf, Named, Local, Deeper and anonymous class 1 of Locals; Ranked, which names Base only as a type argument, is none")
    void classSubtypesExcludeTypeArgumentMentions() {
        assertHierarchy(byName("com.example.hierarchy.Chain.Base"), BASE, List.of(OBJECT), List.of(SINK), List.of(
            MIDDLE, LEAF, NAMED, LOCAL, DEEPER, anonymous("Locals$1", LOCALS, 37)));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Locals$Local finds the local class: it extends Base, implements Sink through it, and has Deeper and anonymous class 1 as subtypes")
    void localClassByModelName() {
        assertHierarchy(byName("com.example.hierarchy.Locals$Local"), LOCAL, List.of(BASE, OBJECT), List.of(SINK),
            List.of(DEEPER, anonymous("Locals$1", LOCALS, 37)));
    }

    private void assertHierarchy(ObjectNode args, Map<String, Object> type, List<Map<String, Object>> superclasses,
                                 List<Map<String, Object>> interfaces, List<Map<String, Object>> subtypes) {
        int total = superclasses.size() + interfaces.size() + subtypes.size();
        Map<String, Object> data = map(
            "type", type,
            "superclasses", superclasses,
            "interfaces", interfaces,
            "subtypes", subtypes,
            "totalSuperclasses", superclasses.size(),
            "totalInterfaces", interfaces.size(),
            "totalSubtypes", subtypes.size());
        assertPayload(mapper, new GetTypeHierarchyTool(() -> service), envelope, args, success(data, map(
            "totalCount", total,
            "returnedCount", total,
            "truncated", false,
            "suggestedNextTools", NEXT_TOOLS)));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(LambdaHierarchy.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private ObjectNode byName(String typeName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", typeName);
        return args;
    }
}
