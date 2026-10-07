package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
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
 * get_document_symbols lists the components of a record declared anywhere in the file, a member
 * record as a top-level one, each as a final recordComponent child ahead of the record's methods.
 * The record's declaration is the one whose name lies at the record's name range. The tool lists
 * top-level and member types only: a local or anonymous class, declared in a method, field or
 * initializer body, is not listed, so a local record contributes no symbol.
 *
 * <p>Shapes.java (codegen-names): Shapes declares the public member record
 * {@code Size(int width, int height)} with the method {@code area()}, and the method
 * {@code total(int)}, whose body declares the local record {@code Run(int start, int end)}. A
 * type's children are its record components, fields, methods and member types, in that order.
 */
class RecordSymbolsTest {

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
    @DisplayName("get_document_symbols of Shapes.java lists the member record Size with its components width and height ahead of area(), and not the local record Run declared in total()")
    void memberRecordComponents() {
        // total() has no children: the local record Run in its body is not listed, and
        // totalSymbols counts Shapes, total, Size, width, height and area only.
        String shapes = CodegenNames.GEN + "Shapes.java";
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(CodegenNames.FIXTURE).resolve(shapes).toString());
        Map<String, Object> size = map(
            "name", "Size",
            "kind", "record",
            "modifiers", List.of("public"),
            "line", 4,
            "children", List.of(
                map("name", "width", "kind", "recordComponent", "modifiers", List.of("final"), "type", "int", "line", 4),
                map("name", "height", "kind", "recordComponent", "modifiers", List.of("final"), "type", "int", "line", 4),
                map("name", "area", "kind", "method", "modifiers", List.of("public"), "signature", "area(): int",
                    "line", 6)));
        Map<String, Object> data = map(
            "file", shapes,
            "symbols", List.of(map(
                "name", "Shapes",
                "kind", "class",
                "modifiers", List.of("public"),
                "line", 2,
                "children", List.of(
                    map("name", "total", "kind", "method", "modifiers", List.of("public"),
                        "signature", "total(int count): int", "line", 11),
                    size))),
            "totalSymbols", 6);
        Map<String, Object> meta = map(
            "totalCount", 6,
            "returnedCount", 6,
            "truncated", false,
            "suggestedNextTools", List.of(
                "go_to_definition to navigate to a symbol",
                "find_references to find usages of a symbol",
                "get_type_members to see members of a specific type"));
        assertPayload(mapper, new GetDocumentSymbolsTool(() -> service), envelope, args, success(data, meta));
    }
}
