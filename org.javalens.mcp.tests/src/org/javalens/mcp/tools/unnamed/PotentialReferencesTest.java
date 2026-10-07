package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.javalens.mcp.tools.FindCastsTool;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.javalens.mcp.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STORE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * Every reference-based tool counts and lists the matches JDT's search confirms and nothing else,
 * and reports the places the search could not confirm, where a type or name does not resolve, in
 * a separate potentialMatches block: the exact count of them and their locations, up to the
 * tool's own limit for its list. The fixture is potential-matches ({@link PotentialMatches}):
 * every reference in Client resolves, and every corresponding place in Stray is a potential
 * match. A match location gives its file, line, column, length and source line; a call's length
 * runs from the method name to the closing parenthesis.
 */
class PotentialReferencesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final Map<String, Object> STRAY_FILL_CALL = at(STRAY, 14, 15, 7, "ledger.fill(3);");
    private static final Map<String, Object> STRAY_FILL_REFERENCE =
        at(STRAY, 17, 37, 4, "IntConsumer filler = ledger::fill;");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("find_references of Store.fill lists Client's three references; Stray's call and method reference on a Ledger are 2 potential matches")
    void methodReferences() {
        Map<String, Object> data = map(
            "symbol", "fill",
            "symbolKind", "method",
            "containingType", PACKAGE + "Store",
            "totalCount", 3,
            "locations", List.of(
                reference(14, 14, "store.fill(1);", "METHOD_INVOCATION"),
                reference(16, 36, "IntConsumer filler = store::fill;", "METHOD_INVOCATION"),
                reference(25, 23, "all.get(0).fill(6);", "METHOD_INVOCATION")),
            "potentialMatches", potential(2, List.of(STRAY_FILL_CALL, STRAY_FILL_REFERENCE)));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(STORE, 6, 16),
            success(data, referencesMeta(3, 3, false)), withoutOffsets());
    }

    @Test
    @DisplayName("find_references of Store.fill with maxResults 1 lists the first reference and the first potential match, each beside its exact count")
    void methodReferencesCut() {
        Map<String, Object> data = map(
            "symbol", "fill",
            "symbolKind", "method",
            "containingType", PACKAGE + "Store",
            "totalCount", 3,
            "locations", List.of(reference(14, 14, "store.fill(1);", "METHOD_INVOCATION")),
            "potentialMatches", potential(2, List.of(STRAY_FILL_CALL)));
        ObjectNode args = position(STORE, 6, 16);
        args.put("maxResults", 1);
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, args,
            success(data, referencesMeta(3, 1, true)), withoutOffsets());
    }

    @Test
    @DisplayName("find_references of the type Store lists Client's 7 references; Stray's 6 uses of an unresolved Store are potential matches")
    void typeReferences() {
        List<Map<String, Object>> references = new ArrayList<>();
        references.add(reference(13, 13, "void use(Store store, Object item) throws Fault {", "TYPE_REFERENCE"));
        List<Map<String, Object>> potentials = new ArrayList<>();
        for (Object[] use : STORE_USES) {
            references.add(reference((int) use[0], (int) use[1], (String) use[2], "TYPE_REFERENCE"));
            potentials.add(at(STRAY, (int) use[0], (int) use[1], 5, (String) use[2]));
        }
        Map<String, Object> data = map(
            "symbol", "Store",
            "symbolKind", "class",
            "totalCount", 7,
            "locations", references,
            "potentialMatches", potential(6, potentials));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(STORE, 2, 13),
            success(data, referencesMeta(7, 7, false)), withoutOffsets());
    }

    @Test
    @DisplayName("find_references of Store.count lists its write in Client and its write and read in Store; the field access on a Ledger is a potential match, the qualified name ledger.count no match")
    void fieldReferences() {
        Map<String, Object> data = map(
            "symbol", "count",
            "symbolKind", "field",
            "containingType", PACKAGE + "Store",
            "totalCount", 3,
            "locations", List.of(
                reference(15, 14, "store.count = 2;", "FIELD_ACCESS"),
                map("filePath", STORE, "line", 7, "column", 8, "context", "count = Math.max(amount, 0);",
                    "referenceKind", "FIELD_ACCESS"),
                map("filePath", STORE, "line", 11, "column", 15, "context", "return count;",
                    "referenceKind", "FIELD_ACCESS")),
            "potentialMatches", potential(1, List.of(at(STRAY, 16, 22, 5, "ledger.self().count = 7;"))));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(STORE, 4, 15),
            success(data, referencesMeta(3, 3, false)), withoutOffsets());
    }

    @Test
    @DisplayName("find_field_writes of Store.count lists its writes in Client and Store; the write through a Ledger is a potential match")
    void fieldWrites() {
        Map<String, Object> data = map(
            "field", "count",
            "declaringType", PACKAGE + "Store",
            "fieldType", "I",
            "totalWriteLocations", 2,
            "writeLocations", List.of(
                map("filePath", CLIENT, "line", 15, "column", 14, "context", "store.count = 2;", "accessType", "WRITE"),
                map("filePath", STORE, "line", 7, "column", 8, "context", "count = Math.max(amount, 0);",
                    "accessType", "WRITE")),
            "potentialMatches", potential(1, List.of(at(STRAY, 16, 22, 5, "ledger.self().count = 7;"))));
        Map<String, Object> meta = map("totalCount", 2, "returnedCount", 2, "truncated", false,
            "suggestedNextTools", List.of(
                "find_references to see all usages (reads and writes)",
                "get_call_hierarchy_incoming to find callers of methods that modify this field"));
        assertPayload(mapper, new FindFieldWritesTool(() -> service), envelope, position(STORE, 4, 15),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("find_method_references of Store.fill lists store::fill; ledger::fill is a potential match")
    void methodReferenceExpressions() {
        Map<String, Object> data = map(
            "methodName", "fill",
            "declaringType", PACKAGE + "Store",
            "totalCount", 1,
            "locations", List.of(at(CLIENT, 16, 36, 4, "IntConsumer filler = store::fill;")),
            "potentialMatches", potential(1, List.of(STRAY_FILL_REFERENCE)));
        Map<String, Object> meta = map("totalCount", 1, "returnedCount", 1, "truncated", false,
            "suggestedNextTools", List.of(
                "find_references for all references including regular calls",
                "get_call_hierarchy_incoming to see all callers"));
        assertPayload(mapper, new FindMethodReferencesTool(() -> service), envelope, position(STORE, 6, 16),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("Each fine-grain tool lists Client's use of the type and reports Stray's use of the unresolved name as a potential match")
    void fineGrainReferences() {
        assertFineGrain(new FindTypeInstantiationsTool(() -> service), "Store",
            List.of(at(CLIENT, 18, 25, 5, "Store made = new Store();")),
            List.of(at(STRAY, 18, 25, 5, "Store made = new Store();")), null,
            List.of("get_type_hierarchy to understand inheritance", "find_references for all references"));
        assertFineGrain(new FindCastsTool(() -> service), "Store",
            List.of(at(CLIENT, 19, 22, 5, "Store cast = (Store) item;")),
            List.of(at(STRAY, 19, 22, 5, "Store cast = (Store) item;")),
            "Casts may indicate design issues - consider polymorphism",
            List.of("find_instanceof_checks to find related type checks", "get_type_hierarchy to understand inheritance"));
        assertFineGrain(new FindInstanceofChecksTool(() -> service), "Store",
            List.of(at(CLIENT, 20, 37, 5, "boolean is = item instanceof Store;")),
            List.of(at(STRAY, 20, 37, 5, "boolean is = item instanceof Store;")),
            "Consider visitor pattern or polymorphism to replace instanceof chains",
            List.of("find_casts to find related cast expressions", "get_type_hierarchy to understand inheritance"));
        assertFineGrain(new FindTypeArgumentsTool(() -> service), "Store",
            List.of(at(CLIENT, 21, 13, 5, "List<Store> all = List.of(made, cast);")),
            List.of(at(STRAY, 21, 13, 5, "List<Store> all = List.of(made, cast);")), null,
            List.of("find_references for all type references", "find_type_instantiations to find object creation"));
        assertFineGrain(new FindThrowsDeclarationsTool(() -> service), "Fault",
            List.of(at(CLIENT, 13, 46, 5, "void use(Store store, Object item) throws Fault {"),
                at(CLIENT, 29, 24, 5, "void risky() throws Fault {")),
            List.of(at(STRAY, 13, 48, 5, "void use(Ledger ledger, Object item) throws Fault {")), null,
            List.of("find_catch_blocks to find handlers for this exception",
                "get_type_hierarchy to see exception hierarchy"));
        assertFineGrain(new FindCatchBlocksTool(() -> service), "Fault",
            List.of(at(CLIENT, 24, 17, 5, "} catch (Fault e) {")),
            List.of(at(STRAY, 24, 17, 5, "} catch (Fault e) {")), null,
            List.of("find_throws_declarations to find sources of this exception",
                "get_type_hierarchy to see exception hierarchy"));
        assertFineGrain(new FindAnnotationUsagesTool(() -> service), "Marker",
            List.of(at(CLIENT, 6, 1, 6, "@Marker")),
            List.of(at(STRAY, 6, 1, 6, "@Marker")), null,
            List.of("get_symbol_info at a usage location for details",
                "find_references for all references (not just annotations)"));
    }

    @Test
    @DisplayName("get_type_usage_summary of Store counts Client's use in each category and reports Stray's beside it as a potential match")
    void usageSummary() {
        Map<String, Object> usages = map(
            "instantiations", category(18, 25, "Store made = new Store();"),
            "casts", category(19, 22, "Store cast = (Store) item;"),
            "instanceofChecks", category(20, 37, "boolean is = item instanceof Store;"),
            "typeArguments", category(21, 13, "List<Store> all = List.of(made, cast);"));
        Map<String, Object> data = map(
            "typeName", PACKAGE + "Store",
            "kind", "class",
            "usages", usages,
            "totalUsages", 4);
        Map<String, Object> meta = map("totalCount", 4, "returnedCount", 4, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_type for complete type analysis",
                "get_type_hierarchy to understand inheritance",
                "find_references for all reference types"));
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + "Store");
        assertPayload(mapper, new GetTypeUsageSummaryTool(() -> service), envelope, args,
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_type of Store counts one use per category and reports the 4 potential uses in Stray beside the counts")
    void typeAnalysisUsages() {
        Map<String, Object> type = map(
            "name", "Store",
            "qualifiedName", PACKAGE + "Store",
            "package", "com.example.potential",
            "kind", "class",
            "modifiers", List.of("public"),
            "file", STORE,
            "line", 2);
        Map<String, Object> members = map(
            "constructors", List.of(),
            "methods", List.of(
                map("name", "fill", "modifiers", List.of("public"), "signature", "fill(int amount): void",
                    "returnType", "void", "line", 6),
                map("name", "level", "modifiers", List.of("public"), "signature", "level(): int",
                    "returnType", "int", "line", 10)),
            "fields", List.of(map("name", "count", "type", "int", "modifiers", List.of("public"), "line", 4)),
            "nestedTypes", List.of(),
            "constructorCount", 0,
            "methodCount", 2,
            "fieldCount", 1,
            "nestedTypeCount", 0);
        Map<String, Object> usages = map(
            "instantiations", 1,
            "casts", 1,
            "instanceofChecks", 1,
            "typeArguments", 1,
            "total", 4,
            "potentialMatches", potential(4, List.of(
                at(STRAY, 18, 25, 5, "Store made = new Store();"),
                at(STRAY, 19, 22, 5, "Store cast = (Store) item;"),
                at(STRAY, 20, 37, 5, "boolean is = item instanceof Store;"),
                at(STRAY, 21, 13, 5, "List<Store> all = List.of(made, cast);"))));
        Map<String, Object> data = map(
            "type", type,
            "members", members,
            "hierarchy", map(),
            "usages", usages,
            "diagnostics", map("errorCount", 0, "warningCount", 0, "hasProblems", false));
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + "Store");
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args,
            success(data, map("suggestedNextTools", List.of(
                "analyze_method for detailed method analysis",
                "find_references for specific symbol usages",
                "get_call_hierarchy_incoming for method callers"))), withoutOffsets());
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming of Store.fill lists Client.use at each of its 3 references; the 2 references on a Ledger are potential matches")
    void incomingCalls() {
        Map<String, Object> data = map(
            "method", "fill",
            "declaringClass", PACKAGE + "Store",
            "signature", "fill(int)",
            "totalCallers", 3,
            "callers", List.of(
                caller(14, 14, "store.fill(1);"),
                caller(16, 36, "IntConsumer filler = store::fill;"),
                caller(25, 23, "all.get(0).fill(6);")),
            "potentialMatches", potential(2, List.of(STRAY_FILL_CALL, STRAY_FILL_REFERENCE)));
        Map<String, Object> meta = map("totalCount", 3, "returnedCount", 3, "truncated", false,
            "suggestedNextTools", List.of(
                "get_call_hierarchy_outgoing to see what this method calls",
                "go_to_definition to navigate to a caller"));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, position(STORE, 6, 16),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_method of Store.fill: one caller, Client.use; Stray's 2 references are potential matches beside the callers; one callee, Math.max")
    void methodAnalysisCallers() {
        Map<String, Object> data = map(
            "method", map(
                "name", "fill",
                "constructor", false,
                "modifiers", List.of("public"),
                "declaringType", PACKAGE + "Store",
                "signature", "fill(int amount): void",
                "returnType", "void",
                "file", STORE,
                "line", 6),
            "parameters", List.of(map("type", "int", "name", "amount")),
            "callers", map(
                "count", 1,
                "list", List.of(map("method", "use", "qualifiedType", PACKAGE + "Client", "file", CLIENT,
                    "callLine", 14)),
                "potentialMatches", potential(2, List.of(STRAY_FILL_CALL, STRAY_FILL_REFERENCE))),
            "callees", map(
                "count", 1,
                "list", List.of(map("name", "max", "kind", "METHOD", "qualifiedType", "java.lang.Math",
                    "signature", "max(int, int)", "callLine", 7))),
            "overrides", map());
        Map<String, Object> meta = map("totalCount", 2, "returnedCount", 2, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(STORE, 6, 16),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_change_impact of Store.fill lists Client's 3 call sites; the 2 places on a Ledger are potential matches at depth 1")
    void changeImpact() {
        Map<String, Object> data = map(
            "symbol", "fill",
            "symbolType", "SourceMethod",
            "depth", 1,
            "directReferences", 3,
            "totalReferences", 3,
            "affectedFiles", List.of(map("filePath", CLIENT, "referenceCount", 3)),
            "callSites", List.of(
                callSite(at(CLIENT, 14, 14, 7, "store.fill(1);")),
                callSite(at(CLIENT, 16, 36, 4, "IntConsumer filler = store::fill;")),
                callSite(at(CLIENT, 25, 23, 7, "all.get(0).fill(6);"))),
            "potentialMatches", potential(2, List.of(atDepth(STRAY_FILL_CALL), atDepth(STRAY_FILL_REFERENCE))));
        Map<String, Object> meta = map("totalCount", 3, "returnedCount", 3,
            "suggestedNextTools", List.of(
                "rename_symbol for safe renaming across all affected files",
                "find_references for simple reference list without grouping"));
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, position(STORE, 6, 16),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("find_reflection_usage lists Client's field.get(target); ledger.get(item), on a Ledger, is a potential call of Field.get")
    void reflectionUsage() {
        Map<String, Object> listed = at(CLIENT, 33, 21, 11, "return field.get(target);");
        listed.put("reflectionMethod", "Field.get");
        Map<String, Object> potentialCall = at(STRAY, 25, 19, 9, "ledger.get(item);");
        potentialCall.put("reflectionMethod", "Field.get");
        Map<String, Object> data = map(
            "totalCalls", 1,
            "summary", map("Field.get", 1),
            "reflectionCalls", List.of(listed),
            "potentialMatches", potential(1, List.of(potentialCall)));
        Map<String, Object> meta = map("totalCount", 1, "returnedCount", 1, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_change_impact to assess risk of renaming reflected types",
                "get_symbol_info at a reflection call site for context"));
        assertPayload(mapper, new FindReflectionUsageTool(() -> service), envelope, mapper.createObjectNode(),
            success(data, meta), withoutOffsets());
    }

    /** Client's and Stray's uses of Store in use(), after its parameter: line, column, source line. */
    private static final Object[][] STORE_USES = {
        {18, 8, "Store made = new Store();"},
        {18, 25, "Store made = new Store();"},
        {19, 8, "Store cast = (Store) item;"},
        {19, 22, "Store cast = (Store) item;"},
        {20, 37, "boolean is = item instanceof Store;"},
        {21, 13, "List<Store> all = List.of(made, cast);"},
    };

    private void assertFineGrain(Tool tool, String simpleName, List<Map<String, Object>> locations,
                                 List<Map<String, Object>> potentials, String advice, List<String> nextTools) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", PACKAGE + simpleName);
        Map<String, Object> data = map(
            "typeName", PACKAGE + simpleName,
            "totalCount", locations.size(),
            "locations", locations);
        if (advice != null) {
            data.put("advice", advice);
        }
        data.put("potentialMatches", potential(potentials.size(), potentials));
        Map<String, Object> meta = map("totalCount", locations.size(), "returnedCount", locations.size(),
            "truncated", false, "suggestedNextTools", nextTools);
        assertPayload(mapper, tool, envelope, args, success(data, meta), withoutOffsets());
    }

    private static Map<String, Object> category(int line, int column, String context) {
        return map(
            "count", 1,
            "locations", List.of(at(CLIENT, line, column, 5, context)),
            "potentialMatches", potential(1, List.of(at(STRAY, line, column, 5, context))));
    }

    private static Map<String, Object> reference(int line, int column, String context, String kind) {
        return map("filePath", CLIENT, "line", line, "column", column, "context", context, "referenceKind", kind);
    }

    private static Map<String, Object> caller(int line, int column, String context) {
        return map("filePath", CLIENT, "line", line, "column", column, "context", context,
            "callerMethod", "use", "callerSignature", "use(Store, Object)", "callerClass", PACKAGE + "Client");
    }

    private static Map<String, Object> callSite(Map<String, Object> location) {
        location.put("depth", 1);
        location.put("enclosingMethod", "use");
        location.put("enclosingType", PACKAGE + "Client");
        return location;
    }

    private static Map<String, Object> atDepth(Map<String, Object> location) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(location);
        copy.put("depth", 1);
        return copy;
    }

    private static Map<String, Object> referencesMeta(int total, int returned, boolean truncated) {
        return map("totalCount", total, "returnedCount", returned, "truncated", truncated,
            "suggestedNextTools", List.of(
                "go_to_definition to see the symbol definition",
                "get_type_hierarchy for type symbols"));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
