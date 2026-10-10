package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ExtractSuperclassTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * extract_superclass lists JDT's warnings in the order of the places they name, by file and
 * position, those naming one place in the order JDT reports them, the same for every load of a
 * project: extract superclass runs pull up's checks (ExtractSupertypeProcessor extends
 * PullUpRefactoringProcessor), which read the fields a moved member uses through a hash set of
 * Java elements (ReferenceFinderUtil.extractElements :173-180) and report the visibility they
 * change by iterating a hash map of Java elements (MemberVisibilityAdjustor.rewriteVisibility
 * :1087, the map PullUpRefactoringProcessor creates at :1708), both in an order that follows the
 * hash of the project's name, which every load makes anew.
 *
 * <p>The fixture is extract-superclass-access, package com.example.extract. Shape.java declares the
 * empty class {@code Shape}. Panel.java declares {@code Panel extends Shape} (line 2, "Shape" at
 * column 27) with the private int fields alpha, beta, gamma, delta, epsilon and zeta (lines 4 to 9,
 * each from column 4) and {@code int total()} (line 11, name at column 8) returning their sum (line
 * 12), its closing brace on line 13 (ending at column 5). Extracting total() into the new superclass
 * Base leaves each private field out of Base's reach: JDT reports an error for each, its place the
 * field (PullUpRefactoringProcessor.checkAccessedFields :1058-1061), then makes each field
 * package-private, as Base, of the same package and no subclass of Panel, needs it
 * (MemberVisibilityAdjustor.thresholdTypeToField :1264-1283), with a warning, its place the field
 * too. extract_superclass lists both kinds as warnings: for each field in declaration order, its
 * error, then its visibility warning. The new file Base.java declares {@code Base extends Shape}
 * with a constructor calling super() and total(), one level deeper than Base, a level being a
 * tab, its body's lines kept relative to the method's own indentation. Panel's edit is the change
 * reduced to its changed span: from "Shape" in its extends clause to total()'s closing brace, Panel
 * extending Base, the fields without "private" and total() with the blank line before it gone.
 * Positions are zero-based; raw offsets are not pinned and texts are compared with "\n" line
 * endings.
 */
class ExtractSuperclassWarningOrderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "extract-superclass-access";
    private static final String PANEL = "src/main/java/com/example/extract/Panel.java";
    private static final List<String> FIELDS = List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta");
    private static final String SUM = "return alpha + beta + gamma + delta + epsilon + zeta;";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Extracting Panel.total into Base warns for alpha to zeta in declaration order, at each of three loads")
    void warningsInDeclarationOrder() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(PANEL).toString());
        args.put("line", 11);
        args.put("column", 8);
        args.put("superclassName", "Base");
        for (int load = 0; load < 3; load++) {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            assertPayload(mapper, new ExtractSuperclassTool(() -> service), new EnvelopeHarness(service), args,
                success(data(), map(
                    "totalCount", 1,
                    "returnedCount", 1,
                    "suggestedNextTools", List.of(
                        "Create the new superclass file from createdFiles",
                        "Apply the text edits to complete the extraction",
                        "get_diagnostics to verify no errors"))),
                NestedTypes.unixFiles());
        }
    }

    private static Map<String, Object> data() {
        List<String> warnings = new ArrayList<>();
        StringBuilder privateFields = new StringBuilder();
        StringBuilder packageFields = new StringBuilder();
        for (String field : FIELDS) {
            warnings.add("Field 'com.example.extract.Panel." + field + "' referenced in one of the moved elements is "
                + "not accessible from type 'com.example.extract.Base'");
            warnings.add("The visibility of field 'com.example.extract.Panel." + field + "' will be changed to package.");
            privateFields.append("\n    ").append("private int ").append(field).append(';');
            packageFields.append("\n    ").append("int ").append(field).append(';');
        }
        return map(
            "superclassName", "Base",
            "memberName", "total",
            "fromType", "com.example.extract.Panel",
            "createdFiles", List.of(map(
                "filePath", "src/main/java/com/example/extract/Base.java",
                "content", "package com.example.extract;\n"
                    + "\n"
                    + "public class Base extends Shape {\n"
                    + "\n"
                    + "\tpublic Base() {\n"
                    + "\t\tsuper();\n"
                    + "\t}\n"
                    + "\n"
                    + "\tint total() {\n"
                    + "\t    " + SUM + "\n"
                    + "\t}\n"
                    + "\n"
                    + "}")),
            "totalEdits", 1,
            "filesAffected", 1,
            "editsByFile", map(
                PANEL, List.of(map("type", "replace", "startLine", 2, "startColumn", 27, "endLine", 13, "endColumn", 5,
                    "oldText", "Shape {\n" + privateFields + "\n\n    int total() {\n        " + SUM + "\n    }",
                    "newText", "Base {\n" + packageFields))),
            "warnings", warnings);
    }
}
