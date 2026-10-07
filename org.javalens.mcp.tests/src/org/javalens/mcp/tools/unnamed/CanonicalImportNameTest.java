package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.SuggestImportsTool;
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
 * suggest_imports names a member type's add_import action by its canonical name, the name an
 * import declaration takes (JLS 6.7, 7.5.1): dots between the enclosing type and the member
 * type. The candidate's fullyQualifiedName keeps the '$' form every tool identifies a nested
 * type by.
 *
 * <p>Folio is the public static member class of com.example.nested.Shelf (nested-types), the one
 * type named Folio in the project or the JDK. A com.* package ranks 40.
 */
class CanonicalImportNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("suggest_imports for Folio offers Shelf$Folio with the action add_import:com.example.nested.Shelf.Folio")
    void memberTypeImportName() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "Folio");
        Map<String, Object> data = map(
            "typeName", "Folio",
            "totalCandidates", 1,
            "candidates", List.of(map(
                "fullyQualifiedName", "com.example.nested.Shelf$Folio",
                "packageName", "com.example.nested",
                "simpleName", "Folio",
                "relevance", 40,
                "isInterface", false,
                "isClass", true,
                "isEnum", false,
                "fixId", "add_import:com.example.nested.Shelf.Folio")));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "apply_quick_fix with fixId to add the import",
                "get_quick_fixes to see all available fixes"));
        assertPayload(mapper, new SuggestImportsTool(() -> service), envelope, args, success(data, meta));
    }
}
