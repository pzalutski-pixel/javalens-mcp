package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.CodegenNames.GEN;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.without;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract_interface gives the interface the import declarations its text needs, as JDT's extract
 * interface collects them from the extracted declarations (ExtractInterfaceProcessor :651,
 * ImportRewriteUtil.collectImports, ImportReferencesCollector): for each type written in an
 * extracted method's type parameter bounds, return type, parameter types and thrown types, and in
 * the class's type parameter bounds, type arguments included, the type its first name denotes.
 * No import is given for a type variable, a top-level type of java.lang or of the interface's
 * package, or a name that starts with a package; a member type is imported by its canonical name.
 * Imports come sorted by name, between the package declaration and the interface.
 *
 * <p>Clerk and Tally (codegen-names) are in com.example.gen; Stamp, its member class Ink and Seal
 * in com.example.gen.model. Clerk imports ArrayList for a method body only.
 */
class ExtractInterfaceImportsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CLERICAL = """
        package com.example.gen;

        import com.example.gen.model.Seal;
        import com.example.gen.model.Stamp;
        import com.example.gen.model.Stamp.Ink;
        import java.io.IOException;
        import java.util.List;
        import java.util.Map.Entry;

        public interface Clerical {

            List<Stamp> stamps(Entry<String,Ink> key) throws IOException;

            Desk.Voucher issue(String note, Route route);

            <S extends Seal> S sealed(S item);

        }
        """;

    private static final String COUNTING = """
        package com.example.gen;

        import com.example.gen.model.Stamp;

        public interface Counting<V extends Stamp> {

            V top();

        }
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(CodegenNames.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("extract_interface of Clerk imports the types its signatures name from other packages, member types Ink and Map.Entry by canonical name, and not String, Route, Desk or ArrayList")
    void methodSignatureImports() {
        Map<String, Object> data = map(
            "className", "Clerk",
            "interfaceName", "Clerical",
            "packageName", "com.example.gen",
            "interfaceFilePath", GEN + "Clerical.java",
            "interfaceContent", CLERICAL,
            "extractedMethods", List.of(
                map("name", "stamps", "signature", "List<Stamp> stamps(Entry<String,Ink> key) throws IOException"),
                map("name", "issue", "signature", "Desk.Voucher issue(String note, Route route)"),
                map("name", "sealed", "signature", "<S extends Seal> S sealed(S item)")),
            "classEdits", List.of(map("type", "insert", "line", 10, "column", 18, "newText", " implements Clerical")),
            "sourceFilePath", GEN + "Clerk.java");
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope,
            args(fixtureFile("Clerk.java"), 10, 13, "Clerical"), success(data, meta(3)),
            without("offset", "data", "classEdits"));
    }

    @Test
    @DisplayName("extract_interface of Tally<V extends Stamp> imports Stamp, named only in the class's type parameter bound")
    void classTypeParameterBoundImport() {
        Map<String, Object> data = map(
            "className", "Tally",
            "interfaceName", "Counting",
            "packageName", "com.example.gen",
            "interfaceFilePath", GEN + "Counting.java",
            "interfaceContent", COUNTING,
            "extractedMethods", List.of(map("name", "top", "signature", "V top()")),
            "classEdits", List.of(map("type", "insert", "line", 4, "column", 35, "newText", " implements Counting<V>")),
            "sourceFilePath", GEN + "Tally.java");
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope,
            args(fixtureFile("Tally.java"), 4, 13, "Counting"), success(data, meta(1)),
            without("offset", "data", "classEdits"));
    }

    @Test
    @DisplayName("the interfaces Clerical and Counting created and their classEdits applied compile, and each class implements its interface")
    @SuppressWarnings("unchecked")
    void appliedExtractionsCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(CodegenNames.FIXTURE);
        Path root = helper.getTempDirectory().resolve(CodegenNames.FIXTURE);
        ExtractInterfaceTool tool = new ExtractInterfaceTool(() -> copy);
        for (Object[] extraction : new Object[][] {{"Clerk.java", 10, "Clerical"}, {"Tally.java", 4, "Counting"}}) {
            Path file = root.resolve(GEN + extraction[0]);
            ToolResponse response = tool.execute(args(file.toString(), (int) extraction[1], 13, (String) extraction[2]));
            assertTrue(response.isSuccess(), () -> "extract_interface failed: " + response.getError());
            Map<String, Object> data = (Map<String, Object>) response.getData();
            Files.writeString(root.resolve((String) data.get("interfaceFilePath")), (String) data.get("interfaceContent"),
                StandardCharsets.UTF_8);
            Files.writeString(file, NestedTypes.applied(Files.readString(file, StandardCharsets.UTF_8),
                (List<Map<String, Object>>) data.get("classEdits")), StandardCharsets.UTF_8);
        }

        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(GEN + "Clerk.java")), "errors in Clerk.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(GEN + "Clerical.java")), "errors in Clerical.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(GEN + "Tally.java")), "errors in Tally.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(GEN + "Counting.java")), "errors in Counting.java"),
            () -> assertEquals(List.of("Clerical"),
                List.of(reloaded.getCompilationUnit(root.resolve(GEN + "Clerk.java")).getType("Clerk").getSuperInterfaceNames())),
            () -> assertEquals(List.of("Counting<V>"),
                List.of(reloaded.getCompilationUnit(root.resolve(GEN + "Tally.java")).getType("Tally").getSuperInterfaceNames())));
    }

    private String fixtureFile(String name) {
        return helper.getFixturePath(CodegenNames.FIXTURE).resolve(GEN + name).toString();
    }

    private static Map<String, Object> meta(int methods) {
        return map(
            "totalCount", methods,
            "returnedCount", methods,
            "suggestedNextTools", List.of(
                "Create the interface file with the provided content",
                "Apply the classEdits to add implements clause",
                "get_diagnostics to verify no errors"));
    }

    private ObjectNode args(String file, int line, int column, String interfaceName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        args.put("column", column);
        args.put("interfaceName", interfaceName);
        return args;
    }
}
