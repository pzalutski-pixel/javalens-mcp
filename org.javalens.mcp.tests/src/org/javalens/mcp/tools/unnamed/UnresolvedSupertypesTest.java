package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * The hierarchy tools report every supertype the compiler cannot resolve, by its name as written,
 * with the type whose declaration names it, beside the supertypes that resolve. The fixture is
 * unresolved-supertypes (package com.example.orphan), whose imports of org.absent name types no
 * classpath entry declares:
 *
 * <ul>
 *   <li>Orphan.java: {@code public class Orphan extends Base implements Shape, Runnable} (line 5),
 *       with {@code run()} (line 7), {@code size()} (line 10) and the static {@code count()} (line 14);
 *       Base and Shape do not resolve.</li>
 *   <li>Heir.java: {@code public class Heir extends Orphan implements Comparable<Heir>} (line 2),
 *       with {@code compareTo(Heir)} (line 4) and {@code size()} (line 8).</li>
 *   <li>Partial.java: {@code public interface Partial extends Shape, Runnable} (line 4), with
 *       {@code width()} (line 6).</li>
 * </ul>
 *
 * <p>A class's superclasses run from the nearest to the last that resolves, then end with
 * java.lang.Object, the superclass of every class. A constructor, a static and a private method
 * override nothing, so no supertype that does not resolve is reported for them. Positions are
 * zero-based.
 */
class UnresolvedSupertypesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "unresolved-supertypes";
    private static final String ORPHAN = "src/main/java/com/example/orphan/Orphan.java";
    private static final String HEIR = "src/main/java/com/example/orphan/Heir.java";
    private static final String PARTIAL = "src/main/java/com/example/orphan/Partial.java";
    private static final String PACKAGE = "com.example.orphan.";

    private static final List<Map<String, Object>> ORPHANS_UNRESOLVED = List.of(
        map("name", "Base", "declaringType", PACKAGE + "Orphan"),
        map("name", "Shape", "declaringType", PACKAGE + "Orphan"));

    private static final Map<String, Object> OBJECT = map("name", "Object", "qualifiedName", "java.lang.Object",
        "kind", "class");
    private static final Map<String, Object> RUNNABLE = map("name", "Runnable", "qualifiedName", "java.lang.Runnable",
        "kind", "interface");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_type_hierarchy of Orphan: superclass Object, interface Runnable, subtype Heir; Base and Shape do not resolve")
    void typeHierarchyOfOrphan() {
        assertHierarchy("Orphan",
            map("name", "Orphan", "qualifiedName", PACKAGE + "Orphan", "kind", "class", "filePath", ORPHAN, "line", 5),
            List.of(OBJECT), List.of(RUNNABLE),
            List.of(map("name", "Heir", "qualifiedName", PACKAGE + "Heir", "kind", "class", "filePath", HEIR,
                "line", 2)),
            ORPHANS_UNRESOLVED);
    }

    @Test
    @DisplayName("get_type_hierarchy of Heir: superclasses Orphan, then Object; interfaces Comparable, then Runnable through Orphan; Orphan's Base and Shape do not resolve")
    void typeHierarchyOfHeir() {
        assertHierarchy("Heir",
            map("name", "Heir", "qualifiedName", PACKAGE + "Heir", "kind", "class", "filePath", HEIR, "line", 2),
            List.of(map("name", "Orphan", "qualifiedName", PACKAGE + "Orphan", "kind", "class", "filePath", ORPHAN,
                "line", 5), OBJECT),
            List.of(map("name", "Comparable", "qualifiedName", "java.lang.Comparable", "kind", "interface"), RUNNABLE),
            List.of(),
            ORPHANS_UNRESOLVED);
    }

    @Test
    @DisplayName("get_type_hierarchy of the interface Partial: interface Runnable; Shape does not resolve")
    void typeHierarchyOfInterface() {
        assertHierarchy("Partial",
            map("name", "Partial", "qualifiedName", PACKAGE + "Partial", "kind", "interface", "filePath", PARTIAL,
                "line", 4),
            List.of(), List.of(RUNNABLE), List.of(),
            List.of(map("name", "Shape", "declaringType", PACKAGE + "Partial")));
    }

    @Test
    @DisplayName("analyze_type of Orphan reports no superclass, its interface Runnable, its subtype Heir, and Base and Shape as unresolved")
    void typeAnalysis() {
        Map<String, Object> data = map(
            "type", map("name", "Orphan", "qualifiedName", PACKAGE + "Orphan", "package", "com.example.orphan",
                "kind", "class", "modifiers", List.of("public"), "file", ORPHAN, "line", 5),
            "members", map(
                "constructors", List.of(),
                "methods", List.of(
                    map("name", "run", "modifiers", List.of("public"), "signature", "run(): void",
                        "returnType", "void", "line", 7),
                    map("name", "size", "modifiers", List.of("public"), "signature", "size(): int",
                        "returnType", "int", "line", 10),
                    map("name", "count", "modifiers", List.of("static"), "signature", "count(): int",
                        "returnType", "int", "line", 14)),
                "fields", List.of(),
                "nestedTypes", List.of(),
                "constructorCount", 0,
                "methodCount", 3,
                "fieldCount", 0,
                "nestedTypeCount", 0),
            "hierarchy", map(
                "interfaces", List.of(map("name", "Runnable", "qualifiedName", "java.lang.Runnable")),
                "unresolvedSupertypes", ORPHANS_UNRESOLVED,
                "subtypes", List.of(map("name", "Heir", "qualifiedName", PACKAGE + "Heir")),
                "subtypeCount", 1),
            // The imports of org.absent.Base and org.absent.Shape, and the names Base and Shape
            // in the declaration, are the file's four errors.
            "diagnostics", map("errorCount", 4, "warningCount", 0, "hasProblems", true));
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + "Orphan");
        args.put("includeUsages", false);
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", List.of(
                "analyze_method for detailed method analysis",
                "find_references for specific symbol usages",
                "get_call_hierarchy_incoming for method callers"))));
    }

    @Test
    @DisplayName("get_type_members of Partial with inherited members lists width and Runnable's run, and reports Shape as unresolved")
    void inheritedMembers() {
        Map<String, Object> data = map(
            "type", map("name", "Partial", "qualifiedName", PACKAGE + "Partial", "kind", "interface",
                "filePath", PARTIAL),
            "methods", List.of(
                map("name", "width", "kind", "method", "modifiers", List.of(), "signature", "width(): int",
                    "line", 6),
                map("name", "run", "kind", "method", "modifiers", List.of("public", "abstract"),
                    "signature", "run(): void", "declaredIn", "java.lang.Runnable")),
            "fields", List.of(),
            "nestedTypes", List.of(),
            "totalMembers", 2,
            "unresolvedSupertypes", List.of(map("name", "Shape", "declaringType", PACKAGE + "Partial")));
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + "Partial");
        args.put("includeInherited", true);
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, args,
            success(data, map("totalCount", 2, "returnedCount", 2, "suggestedNextTools", List.of(
                "find_references to find usages of a member",
                "go_to_definition to navigate to member source",
                "get_type_hierarchy to see inheritance chain"))));
    }

    @Test
    @DisplayName("get_super_method of Orphan.size finds no overridden method of a resolved supertype and reports Base and Shape as unresolved")
    void superMethodNone() {
        Map<String, Object> data = map(
            "method", superInfo("size", PACKAGE + "Orphan", "size(): int", ORPHAN, 10),
            "overrides", null,
            "message", "This method does not override or implement a method of a supertype that resolves; "
                + "the supertypes in unresolvedSupertypes do not resolve",
            "unresolvedSupertypes", ORPHANS_UNRESOLVED);
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, position(ORPHAN, 10, 15),
            success(data, superMethodMeta()));
    }

    @Test
    @DisplayName("get_super_method of Heir.size finds Orphan.size and reports Orphan's Base and Shape as unresolved")
    void superMethodFound() {
        Map<String, Object> data = map(
            "method", superInfo("size", PACKAGE + "Heir", "size(): int", HEIR, 8),
            "overrides", superInfo("size", PACKAGE + "Orphan", "size(): int", ORPHAN, 10),
            "implementsInterface", false,
            "unresolvedSupertypes", ORPHANS_UNRESOLVED);
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, position(HEIR, 8, 15),
            success(data, superMethodMeta()));
    }

    @Test
    @DisplayName("analyze_method of Orphan.size overrides nothing that resolves, is overridden by Heir.size, and reports Base and Shape as unresolved")
    void methodAnalysisOverrides() {
        Map<String, Object> data = map(
            "method", map("name", "size", "constructor", false, "modifiers", List.of("public"),
                "declaringType", PACKAGE + "Orphan", "signature", "size(): int", "returnType", "int",
                "file", ORPHAN, "line", 10),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map(
                "overriddenBy", List.of(map("method", "size", "qualifiedType", PACKAGE + "Heir")),
                "unresolvedSupertypes", ORPHANS_UNRESOLVED));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(ORPHAN, 10, 15),
            success(data, map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"))));
    }

    @Test
    @DisplayName("get_super_method of the static Orphan.count finds no overridden method and reports no unresolved supertype: a static method overrides nothing")
    void superMethodOfStaticMethod() {
        Map<String, Object> data = map(
            "method", map("name", "count", "declaringType", PACKAGE + "Orphan", "signature", "count(): int",
                "modifiers", List.of("static"), "filePath", ORPHAN, "line", 14),
            "overrides", null,
            "message", "This method does not override or implement any method");
        assertPayload(mapper, new GetSuperMethodTool(() -> service), envelope, position(ORPHAN, 14, 15),
            success(data, superMethodMeta()));
    }

    @Test
    @DisplayName("analyze_method of the static Orphan.count reports no override information and no unresolved supertype")
    void methodAnalysisOfStaticMethod() {
        Map<String, Object> data = map(
            "method", map("name", "count", "constructor", false, "modifiers", List.of("static"),
                "declaringType", PACKAGE + "Orphan", "signature", "count(): int", "returnType", "int",
                "file", ORPHAN, "line", 14),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(ORPHAN, 14, 15),
            success(data, map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"))));
    }

    private void assertHierarchy(String simpleName, Map<String, Object> type, List<Map<String, Object>> superclasses,
                                 List<Map<String, Object>> interfaces, List<Map<String, Object>> subtypes,
                                 List<Map<String, Object>> unresolved) {
        int total = superclasses.size() + interfaces.size() + subtypes.size();
        Map<String, Object> data = map(
            "type", type,
            "superclasses", superclasses,
            "interfaces", interfaces,
            "unresolvedSupertypes", unresolved,
            "subtypes", subtypes,
            "totalSuperclasses", superclasses.size(),
            "totalInterfaces", interfaces.size(),
            "totalSubtypes", subtypes.size());
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + simpleName);
        assertPayload(mapper, new GetTypeHierarchyTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "truncated", false,
                "suggestedNextTools", List.of(
                    "get_type_members to see members of a specific type",
                    "find_implementations to find all implementors",
                    "find_references to see where type is used"))));
    }

    private static Map<String, Object> superInfo(String name, String declaringType, String signature, String file,
                                                 int line) {
        return map("name", name, "declaringType", declaringType, "signature", signature,
            "modifiers", List.of("public"), "filePath", file, "line", line);
    }

    private static Map<String, Object> superMethodMeta() {
        return map("suggestedNextTools", List.of(
            "find_implementations to find overriding methods",
            "get_type_hierarchy for inheritance chain"));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
