package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.LOCALS;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * get_type_members finds a local or anonymous class named in the Java model's '$' form, and with
 * includeInherited lists the members of every superclass, nearest first, then the methods of every
 * interface, as the model holds them, for a class declared in or after a lambda body too. A '$'
 * name that two local classes share, or that names no class, finds none. The fixture is
 * lambda-hierarchy ({@link LambdaHierarchy}).
 *
 * <p>The inherited java.lang.Object methods come from the JDK running the tests (binary members,
 * parameter names with or without attached source), so an exact list is not derivable from the
 * fixture: they are pinned as the methods between the class's own and its interfaces', every one
 * declared in java.lang.Object, among them toString, equals and hashCode.
 */
class InheritedMembersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "find_references to find usages of a member",
        "go_to_definition to navigate to member source",
        "get_type_hierarchy to see inheritance chain");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Host$5, includeInherited: its own tag, Object's methods, then Mark's tag")
    void anonymousClassAfterLambdaInheritsFromItsInterface() {
        assertInherited(args(PACKAGE + "Host$5", true),
            typeBlock("Host$5", "Host$5", HOST),
            List.of(method("tag", List.of("public"), "tag(): String", null, 36)),
            List.of(method("tag", List.of(), "tag(): String", PACKAGE + "Host$Mark", 8)));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Locals$Deeper, includeInherited: Local's put, Base's put, count and weight, Object's methods, then Sink's put")
    void localClassInheritsThroughLocalSuperclass() {
        assertInherited(args(PACKAGE + "Locals$Deeper", true),
            typeBlock("Deeper", "Locals$Deeper", LOCALS),
            List.of(
                method("put", List.of("public"), "put(String item): void", PACKAGE + "Locals$Local", 32),
                method("put", List.of("public"), "put(String item): void", PACKAGE + "Chain$Base", 13),
                method("count", List.of("static"), "count(): int", PACKAGE + "Chain$Base", 16),
                method("weight", List.of(), "weight(): int", PACKAGE + "Chain$Base", 20)),
            List.of(method("put", List.of(), "put(T item): void", PACKAGE + "Chain$Sink", 5)));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Locals$Local finds the local class: its one method put")
    void localClassByModelName() {
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, args(PACKAGE + "Locals$Local", null),
            members(typeBlock("Local", "Locals$Local", LOCALS),
                List.of(method("put", List.of("public"), "put(String item): void", null, 32))));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Locals$Mode$1 finds the body of the constant LOUD, of kind enum as the model flags it: its one method shout")
    void enumConstantBodyByModelName() {
        Map<String, Object> body = typeBlock("Locals$Mode$1", "Locals$Mode$1", LOCALS);
        body.put("kind", "enum");
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, args(PACKAGE + "Locals$Mode$1", null),
            members(body, List.of(method("shout", List.of(), "shout(): String", null, 10))));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Twins$Echo, the name of two local classes, finds none: SYMBOL_NOT_FOUND")
    void sharedLocalNameIsNotFound() {
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, args(PACKAGE + "Twins$Echo", null),
            notFound(PACKAGE + "Twins$Echo"));
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Host$9, an anonymous class Host does not have, finds none: SYMBOL_NOT_FOUND")
    void missingAnonymousClassIsNotFound() {
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, args(PACKAGE + "Host$9", null),
            notFound(PACKAGE + "Host$9"));
    }

    /**
     * The includeInherited payload: {@code own} methods first, then java.lang.Object's (see the
     * class comment), then {@code interfaceMethods}; no fields and no nested types.
     */
    private void assertInherited(ObjectNode args, Map<String, Object> type, List<Map<String, Object>> own,
                                 List<Map<String, Object>> interfaceMethods) {
        JsonNode payload = envelope.assertEnvelopeFidelity("get_type_members", args);
        JsonNode data = payload.path("data");
        List<JsonNode> methods = new ArrayList<>();
        data.path("methods").forEach(methods::add);
        int count = methods.size();
        int objectEnd = count - interfaceMethods.size();
        List<JsonNode> objectMethods = methods.subList(own.size(), objectEnd);
        Set<String> objectNames = new TreeSet<>();
        Set<String> objectDeclaredIn = new TreeSet<>();
        for (JsonNode method : objectMethods) {
            objectNames.add(method.path("name").asText());
            objectDeclaredIn.add(method.path("declaredIn").asText());
        }
        assertAll(
            () -> assertEquals(true, payload.path("success").asBoolean(), "success"),
            () -> assertEquals(mapper.valueToTree(type), data.path("type"), "type block"),
            () -> assertEquals(mapper.valueToTree(own), mapper.valueToTree(methods.subList(0, own.size())),
                "the class's own and its superclasses' methods come first, nearest first"),
            () -> assertEquals(mapper.valueToTree(interfaceMethods), mapper.valueToTree(methods.subList(objectEnd, count)),
                "the interfaces' methods come last"),
            () -> assertEquals(Set.of("java.lang.Object"), objectDeclaredIn,
                "every method between them is declared in java.lang.Object"),
            () -> assertTrue(objectNames.containsAll(Set.of("toString", "equals", "hashCode")),
                "bound, see the class comment: Object's methods include toString, equals and hashCode; got " + objectNames),
            () -> assertEquals(mapper.valueToTree(List.of()), data.path("fields"), "no class here declares a field"),
            () -> assertEquals(mapper.valueToTree(List.of()), data.path("nestedTypes"), "member types are not inherited"),
            () -> assertEquals(count, data.path("totalMembers").asInt(), "totalMembers counts every method"),
            () -> assertEquals(mapper.valueToTree(map("totalCount", count, "returnedCount", count,
                "suggestedNextTools", NEXT_TOOLS)), payload.path("meta"), "meta"));
    }

    private static Map<String, Object> members(Map<String, Object> type, List<Map<String, Object>> methods) {
        Map<String, Object> data = map(
            "type", type,
            "methods", methods,
            "fields", List.of(),
            "nestedTypes", List.of(),
            "totalMembers", methods.size());
        return success(data, map("totalCount", methods.size(), "returnedCount", methods.size(),
            "suggestedNextTools", NEXT_TOOLS));
    }

    private static Map<String, Object> notFound(String typeName) {
        return map("success", false, "error", map(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: Type not found: " + typeName,
            "hint", "Use search_symbols to find available symbols"));
    }

    private static Map<String, Object> typeBlock(String name, String typeQualifiedName, String file) {
        return map("name", name, "qualifiedName", PACKAGE + typeQualifiedName, "kind", "class", "filePath", file);
    }

    /** A method entry; {@code declaredIn} is set for an inherited method only. */
    private static Map<String, Object> method(String name, List<String> modifiers, String signature,
                                              String declaredIn, int line) {
        Map<String, Object> method = map("name", name, "kind", "method", "modifiers", modifiers, "signature", signature);
        if (declaredIn != null) {
            method.put("declaredIn", declaredIn);
        }
        method.put("line", line);
        return method;
    }

    private ObjectNode args(String typeName, Boolean includeInherited) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", typeName);
        if (includeInherited != null) {
            args.put("includeInherited", includeInherited);
        }
        return args;
    }
}
