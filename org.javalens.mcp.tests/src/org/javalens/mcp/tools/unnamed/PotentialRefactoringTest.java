package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STORE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * rename_symbol and change_method_signature edit the declaration and the references JDT's search
 * confirms, and never a place it could not confirm: Stray's call and method reference on a Ledger,
 * a type the compiler cannot find, are reported as potential matches and left unedited. The
 * fixture is potential-matches ({@link PotentialMatches}). Raw offsets are left out; line and
 * column pin each edit.
 */
class PotentialRefactoringTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final Map<String, Object> STRAY_POTENTIALS = potential(2, List.of(
        at(STRAY, 14, 15, 7, "ledger.fill(3);"),
        at(STRAY, 17, 37, 4, "IntConsumer filler = ledger::fill;")));

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("rename_symbol Store.fill to pour edits the declaration and Client's 3 references, never Stray's 2 potential matches")
    void rename() {
        Map<String, Object> data = map(
            "oldName", "fill",
            "newName", "pour",
            "symbolKind", "method",
            "totalEdits", 4,
            "filesAffected", 2,
            "editsByFile", map(
                STORE, List.of(renameEdit(6, 16)),
                CLIENT, List.of(renameEdit(25, 23), renameEdit(16, 36), renameEdit(14, 14))),
            "potentialMatches", STRAY_POTENTIALS);
        Map<String, Object> meta = map("totalCount", 4, "returnedCount", 4,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the rename",
                "get_diagnostics to verify no errors after rename"));
        ObjectNode args = position();
        args.put("newName", "pour");
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args, success(data, meta),
            withoutOffsets());
    }

    @Test
    @DisplayName("change_method_signature renaming Store.fill to pour edits the declaration and Client's 2 calls, reports store::fill, and never edits Stray's 2 potential matches")
    void changeSignature() {
        Map<String, Object> data = map(
            "oldName", "fill",
            "newName", "pour",
            "oldReturnType", "void",
            "newReturnType", "void",
            "oldParameterCount", 1,
            "newParameterCount", 1,
            "newParameters", List.of(map("name", "amount", "type", "int")),
            "totalEdits", 3,
            "filesAffected", 2,
            "editsByFile", map(
                STORE, List.of(map("type", "replace", "startLine", 6, "startColumn", 11, "endLine", 6,
                    "endColumn", 32, "oldSignature", "void fill(int amount)", "newSignature", "void pour(int amount)",
                    "isDeclaration", true)),
                CLIENT, List.of(
                    callEdit(14, 8, 21, "store.fill(1)", "store.pour(1)"),
                    callEdit(25, 12, 30, "all.get(0).fill(6)", "all.get(0).pour(6)"))),
            "methodReferences", List.of(map("filePath", CLIENT, "line", 16, "column", 29, "text", "store::fill",
                "reason", "Method reference cannot be automatically rewritten; the functional-interface "
                    + "signature would no longer match. Replace with a lambda or refactor manually.")),
            "warnings", List.of(CLIENT + " has method-reference call sites that cannot be automatically updated. "
                + "Replace each with a lambda or refactor the functional-interface signature."),
            "potentialMatches", STRAY_POTENTIALS);
        Map<String, Object> meta = map("totalCount", 3, "returnedCount", 3,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the signature change",
                "get_diagnostics to verify no errors"));
        ObjectNode args = position();
        args.put("newName", "pour");
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args, success(data, meta),
            withoutOffsets());
    }

    private static Map<String, Object> renameEdit(int line, int column) {
        return map("line", line, "column", column, "endColumn", column + 4, "oldText", "fill", "newText", "pour");
    }

    private static Map<String, Object> callEdit(int line, int startColumn, int endColumn, String oldText,
                                                String newText) {
        return map("type", "replace", "startLine", line, "startColumn", startColumn, "endLine", line,
            "endColumn", endColumn, "oldText", oldText, "newText", newText, "isDeclaration", false);
    }

    private ObjectNode position() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(STORE).toString());
        args.put("line", 6);
        args.put("column", 16);
        return args;
    }
}
