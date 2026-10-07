package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetQuickFixesTool;
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
 * get_quick_fixes names a member type's add_import fix by its canonical name, the name an import
 * declaration takes (JLS 6.7, 7.5.1): dots between the enclosing type and the member type, as
 * suggest_imports names it. A type in the default package cannot be imported (JLS 7.5), so it
 * is never offered. The label names the package of the type to import.
 *
 * <p>Ticket.java (codegen-names) line 4 declares {@code Voucher voucher} without an import. Two
 * types are named Voucher: the public static member class com.example.gen.Desk.Voucher and the
 * default-package Voucher; the JDK declares none. A com.* package ranks 40.
 */
class QuickFixImportNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(CodegenNames.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_quick_fixes for the unresolved Voucher offers add_import:com.example.gen.Desk.Voucher and not the default-package Voucher")
    void memberTypeImportName() {
        String ticket = CodegenNames.GEN + "Ticket.java";
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(CodegenNames.FIXTURE).resolve(ticket).toString());
        args.put("line", 4);
        Map<String, Object> data = map(
            "filePath", ticket,
            "line", 4,
            "problemCount", 1,
            "problems", List.of(map(
                "problemId", 16777218,
                "message", "Voucher cannot be resolved to a type",
                "severity", "error")),
            "fixes", List.of(map(
                "fixId", "add_import:com.example.gen.Desk.Voucher",
                "label", "Import 'Voucher' (com.example.gen)",
                "category", "IMPORT",
                "relevance", 40,
                "problemId", 16777218)));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "apply_quick_fix with fixId to apply a fix",
                "get_diagnostics to see all problems"));
        assertPayload(mapper, new GetQuickFixesTool(() -> service), envelope, args, success(data, meta));
    }
}
