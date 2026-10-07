package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.HOST;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.LOCALS;
import static org.javalens.mcp.tools.unnamed.LambdaHierarchy.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * analyze_type reports a type's direct superclass (unless Object), its direct interfaces and all
 * its subtypes as the Java model holds them, anonymous and local classes declared in lambda bodies
 * included, and analyzes a local or anonymous class named in the model's '$' form. The fixture is
 * lambda-hierarchy ({@link LambdaHierarchy}); Host.java compiles with one warning, Locals.java
 * with none.
 */
class AnalyzeTypeHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "analyze_method for detailed method analysis",
        "find_references for specific symbol usages",
        "get_call_hierarchy_incoming for method callers");

    private static final Map<String, Object> HOST_DIAGNOSTICS = map("errorCount", 0, "warningCount", 1, "hasProblems", true);

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(LambdaHierarchy.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Host.Mark's subtypes are anonymous classes 1, 2 (in a lambda body), 3 and 5")
    void interfaceSubtypesInAndAfterLambda() {
        assertAnalysis("com.example.hierarchy.Host.Mark",
            typeInfo("Mark", "Host$Mark", "interface", List.of(), HOST, 7),
            members(List.of(method("tag", List.of(), "tag(): String", "String", 8))),
            map("subtypes", List.of(named("Host$1"), named("Host$2"), named("Host$3"), named("Host$5")),
                "subtypeCount", 4),
            HOST_DIAGNOSTICS);
    }

    @Test
    @DisplayName("Host.Plate's subtypes are the local classes Thin (in a lambda body) and Thick, and anonymous class 6")
    void classSubtypesInAndAfterLambda() {
        assertAnalysis("com.example.hierarchy.Host.Plate",
            typeInfo("Plate", "Host$Plate", "class", List.of("static"), HOST, 11),
            members(List.of(method("size", List.of(), "size(): int", "int", 12))),
            map("subtypes", List.of(map("name", "Thin", "qualifiedName", PACKAGE + "Host$Thin"),
                    map("name", "Thick", "qualifiedName", PACKAGE + "Host$Thick"), named("Host$6")),
                "subtypeCount", 3),
            HOST_DIAGNOSTICS);
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Host$2 finds anonymous class 2, created in a lambda body: its one interface is Mark, its superclass Object is omitted")
    void anonymousClassInLambdaByModelName() {
        assertAnalysis("com.example.hierarchy.Host$2",
            typeInfo("Host$2", "Host$2", "class", List.of(), HOST, 23),
            members(List.of(method("tag", List.of("public"), "tag(): String", "String", 24))),
            map("interfaces", List.of(map("name", "Mark", "qualifiedName", PACKAGE + "Host$Mark"))),
            HOST_DIAGNOSTICS);
    }

    @Test
    @DisplayName("typeName com.example.hierarchy.Locals$Local finds the local class: superclass Base, subtypes Deeper and anonymous class 1, a Local created in a lambda body")
    void localClassByModelName() {
        assertAnalysis("com.example.hierarchy.Locals$Local",
            typeInfo("Local", "Locals$Local", "class", List.of(), LOCALS, 31),
            members(List.of(method("put", List.of("public"), "put(String item): void", "void", 32))),
            map("superclass", map("name", "Base", "qualifiedName", PACKAGE + "Chain$Base"),
                "subtypes", List.of(map("name", "Deeper", "qualifiedName", PACKAGE + "Locals$Deeper"), named("Locals$1")),
                "subtypeCount", 2),
            map("errorCount", 0, "warningCount", 0, "hasProblems", false));
    }

    private void assertAnalysis(String typeName, Map<String, Object> type, Map<String, Object> members,
                                Map<String, Object> hierarchy, Map<String, Object> diagnostics) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", typeName);
        args.put("includeUsages", false);
        Map<String, Object> data = map(
            "type", type,
            "members", members,
            "hierarchy", hierarchy,
            "diagnostics", diagnostics);
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", NEXT_TOOLS)));
    }

    private static Map<String, Object> typeInfo(String name, String typeQualifiedName, String kind,
                                                List<String> modifiers, String file, int line) {
        return map(
            "name", name,
            "qualifiedName", PACKAGE + typeQualifiedName,
            "package", "com.example.hierarchy",
            "kind", kind,
            "modifiers", modifiers,
            "file", file,
            "line", line);
    }

    /** A type's members: no constructors, fields or nested types, and the given methods. */
    private static Map<String, Object> members(List<Map<String, Object>> methods) {
        return map(
            "constructors", List.of(),
            "methods", methods,
            "fields", List.of(),
            "nestedTypes", List.of(),
            "constructorCount", 0,
            "methodCount", methods.size(),
            "fieldCount", 0,
            "nestedTypeCount", 0);
    }

    private static Map<String, Object> method(String name, List<String> modifiers, String signature,
                                              String returnType, int line) {
        return map("name", name, "modifiers", modifiers, "signature", signature, "returnType", returnType,
            "line", line);
    }

    /** An anonymous class as a hierarchy entry: its type-qualified name and its qualified name. */
    private static Map<String, Object> named(String typeQualifiedName) {
        return map("name", typeQualifiedName, "qualifiedName", PACKAGE + typeQualifiedName);
    }
}
