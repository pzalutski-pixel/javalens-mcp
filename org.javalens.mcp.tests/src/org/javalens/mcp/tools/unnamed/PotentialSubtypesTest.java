package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PROBE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * The tools that list a type's subtypes list those JDT's search confirms, and report a class whose
 * extends or implements clause names the type by a name the compiler cannot resolve as a potential
 * match: Stray implements Probe, which is not imported into its package, so the search for Probe's
 * implementors reports it as A_INACCURATE (SuperTypeReferenceLocator.resolveLevel(ASTNode) :115).
 * The fixture is potential-matches ({@link PotentialMatches}).
 */
class PotentialSubtypesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final Map<String, Object> STRAY_IMPLEMENTS =
        at(STRAY, 7, 30, 5, "public class Stray implements Probe {");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_type_hierarchy of Probe lists Client as its subtype and Stray's implements clause as a potential match")
    void typeHierarchy() {
        Map<String, Object> data = map(
            "type", map("name", "Probe", "qualifiedName", PACKAGE + "Probe", "kind", "interface",
                "filePath", PROBE, "line", 2),
            "superclasses", List.of(),
            "interfaces", List.of(),
            "subtypes", List.of(map("name", "Client", "qualifiedName", PACKAGE + "Client", "kind", "class",
                "filePath", CLIENT, "line", 6)),
            "totalSuperclasses", 0,
            "totalInterfaces", 0,
            "totalSubtypes", 1,
            "potentialMatches", potential(1, List.of(STRAY_IMPLEMENTS)));
        Map<String, Object> meta = map("totalCount", 1, "returnedCount", 1, "truncated", false,
            "suggestedNextTools", List.of(
                "get_type_members to see members of a specific type",
                "find_implementations to find all implementors",
                "find_references to see where type is used"));
        assertPayload(mapper, new GetTypeHierarchyTool(() -> service), envelope, position(2, 17),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("find_implementations of Probe lists Client; Stray, whose Probe does not resolve, is a potential match")
    void typeImplementations() {
        Map<String, Object> data = map(
            "symbol", "Probe",
            "isInterface", true,
            "totalImplementations", 1,
            "implementations", List.of(map("name", "Client", "qualifiedName", PACKAGE + "Client", "kind", "class",
                "filePath", CLIENT, "line", 7, "column", 13)),
            "potentialMatches", potential(1, List.of(STRAY_IMPLEMENTS)));
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope, position(2, 17),
            success(data, implementationsMeta()), withoutOffsets());
    }

    @Test
    @DisplayName("find_implementations of Probe.probe lists Client.probe; Stray, a potential implementor, is a potential match")
    void methodImplementations() {
        Map<String, Object> data = map(
            "symbol", "Probe",
            "isInterface", true,
            "method", "probe",
            "totalImplementations", 1,
            "implementations", List.of(map("name", "Client", "qualifiedName", PACKAGE + "Client", "kind", "class",
                "method", "probe", "filePath", CLIENT, "line", 9, "column", 15)),
            "potentialMatches", potential(1, List.of(STRAY_IMPLEMENTS)));
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope, position(3, 8),
            success(data, implementationsMeta()), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_type of Probe lists Client as its subtype and Stray's implements clause as a potential match in its hierarchy")
    void typeAnalysisHierarchy() {
        Map<String, Object> data = map(
            "type", map("name", "Probe", "qualifiedName", PACKAGE + "Probe", "package", "com.example.potential",
                "kind", "interface", "modifiers", List.of("public"), "file", PROBE, "line", 2),
            "members", map(
                "constructors", List.of(),
                "methods", List.of(map("name", "probe", "modifiers", List.of(), "signature", "probe(): int",
                    "returnType", "int", "line", 3)),
                "fields", List.of(),
                "nestedTypes", List.of(),
                "constructorCount", 0,
                "methodCount", 1,
                "fieldCount", 0,
                "nestedTypeCount", 0),
            "hierarchy", map(
                "subtypes", List.of(map("name", "Client", "qualifiedName", PACKAGE + "Client")),
                "subtypeCount", 1,
                "potentialMatches", potential(1, List.of(STRAY_IMPLEMENTS))),
            "diagnostics", map("errorCount", 0, "warningCount", 0, "hasProblems", false));
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + "Probe");
        args.put("includeUsages", false);
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", List.of(
                "analyze_method for detailed method analysis",
                "find_references for specific symbol usages",
                "get_call_hierarchy_incoming for method callers"))), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_method of Probe.probe: overridden by Client.probe; Stray's implements clause is a potential match beside the overriders")
    void methodAnalysisOverriders() {
        Map<String, Object> data = map(
            "method", map("name", "probe", "constructor", false, "modifiers", List.of(),
                "declaringType", PACKAGE + "Probe", "signature", "probe(): int", "returnType", "int",
                "file", PROBE, "line", 3),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 0, "list", List.of()),
            "overrides", map(
                "overriddenBy", List.of(map("method", "probe", "qualifiedType", PACKAGE + "Client")),
                "potentialMatches", potential(1, List.of(STRAY_IMPLEMENTS))));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(3, 8),
            success(data, map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"))), withoutOffsets());
    }

    private static Map<String, Object> implementationsMeta() {
        return map("totalCount", 1, "returnedCount", 1, "truncated", false,
            "suggestedNextTools", List.of(
                "get_type_hierarchy for full inheritance chain",
                "find_references to see all usages",
                "get_type_members to see members of an implementation"));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(PROBE).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
