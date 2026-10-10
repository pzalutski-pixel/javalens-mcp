package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.FaultyService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A failure while a tool reads what it answers is the answer: INTERNAL_ERROR with the failure's
 * message, never a success that leaves out what could not be read. Each test loads simple-maven
 * into a {@link FaultyService} that fails one service call, or whose compilation units answer
 * reconcile with no syntax tree, and asserts the whole payload through execute() and through the
 * JSON-RPC envelope.
 */
class FailureSurfacesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String METHOD_REF_TARGET = "src/main/java/com/example/MethodRefTarget.java";

    private static final Map<String, Object> INJECTED = internalError("Internal error: " + FaultyService.FAILURE);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("find_references whose reference location cannot be read is INTERNAL_ERROR")
    void findReferences_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getContextLine");
        assertFails(service, FindReferencesTool::new, at(CALCULATOR, 14, 15), INJECTED);
    }

    @Test
    @DisplayName("find_method_references whose match location cannot be read is INTERNAL_ERROR")
    void findMethodReferences_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getContextLine");
        assertFails(service, FindMethodReferencesTool::new, at(METHOD_REF_TARGET, 8, 25), INJECTED);
    }

    @Test
    @DisplayName("get_di_registrations whose annotation type lookup fails is INTERNAL_ERROR")
    void diRegistrations_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, GetDiRegistrationsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("find_reflection_usage whose reflection type lookup fails is INTERNAL_ERROR")
    void reflectionUsage_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, FindReflectionUsageTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("rename_symbol whose edit position cannot be read is INTERNAL_ERROR")
    void renameSymbol_unreadablePosition() throws Exception {
        FaultyService service = load().failing("getLineNumber");
        ObjectNode args = at(CALCULATOR, 14, 15);
        args.put("newName", "sum");
        assertFails(service, RenameSymbolTool::new, args, INJECTED);
    }

    @Test
    @DisplayName("find_possible_bugs over the project, a file of which cannot be read, is INTERNAL_ERROR")
    void possibleBugs_unreadableFile() throws Exception {
        FaultyService service = load().failing("getCompilationUnit");
        assertFails(service, FindPossibleBugsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("get_quick_fixes on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void quickFixes_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(CALCULATOR).toString());
        args.put("line", 14);
        assertFails(service, GetQuickFixesTool::new, args,
            internalError("Internal error: JDT produced no syntax tree for " + CALCULATOR));
    }

    @Test
    @DisplayName("get_diagnostics on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void diagnostics_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(CALCULATOR).toString());
        assertFails(service, GetDiagnosticsTool::new, args,
            internalError("Internal error: JDT produced no syntax tree for " + CALCULATOR));
    }

    private FaultyService load() throws Exception {
        FaultyService service = new FaultyService();
        service.loadProject(fixture());
        return service;
    }

    private Path fixture() {
        return helper.getFixturePath("simple-maven");
    }

    private ObjectNode at(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> internalError(String message) {
        return Map.of("success", false, "error", Map.of(
            "code", "INTERNAL_ERROR",
            "message", message,
            "hint", "This may be a bug. Check server logs for details."));
    }

    /**
     * The payload of the tool {@code create} makes over {@code service}, through execute() and
     * through the JSON-RPC envelope, both equal {@code expected}.
     */
    private void assertFails(FaultyService service, Function<Supplier<IJdtService>, Tool> create,
                             ObjectNode args, Map<String, Object> expected) throws Exception {
        Tool tool = create.apply(() -> service);
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct = mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy())));
        JsonNode wire = new EnvelopeHarness(service).assertEnvelopeFidelity(tool.getName(), args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
