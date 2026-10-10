package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * The implicitly declared class of a compact source file (JEP 512) has a hierarchy like any class:
 * its superclass is java.lang.Object, and it declares no interface and can have no subtype. Its
 * name node has no position in the source, so it is found as its unit's primary type, the type the
 * model names after the file.
 *
 * <p>The fixture is java25-maven's CompactMain.java: a three-line {@code ///} doc comment (lines 0
 * to 2) on the field {@code String greeting = "Hello";} (line 3), {@code void main()} (line 5) and
 * {@code String message()} (line 9). The parser starts the implicit class at its first member's
 * declaration, the doc comment included
 * (Parser.consumeInternalCompilationUnitWithPotentialImplicitlyDeclaredClass :4629-4668), offset 0,
 * so the class and the field are on line 0; the class is final (ImplicitTypeDeclaration :27) and
 * the model holds no default constructor (SourceElementNotifier :270). The file compiles cleanly.
 * Lines are zero-based.
 */
class ImplicitClassHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FILE = "src/main/java/CompactMain.java";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("get_type_hierarchy of CompactMain: superclass java.lang.Object, no interface, no subtype")
    void typeHierarchy() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "CompactMain");
        assertPayload(mapper, new GetTypeHierarchyTool(() -> service), new EnvelopeHarness(service), args,
            success(map(
                "type", map("name", "CompactMain", "qualifiedName", "CompactMain", "kind", "class",
                    "filePath", FILE, "line", 0),
                "superclasses", List.of(map("name", "Object", "qualifiedName", "java.lang.Object", "kind", "class")),
                "interfaces", List.of(),
                "subtypes", List.of(),
                "totalSuperclasses", 1,
                "totalInterfaces", 0,
                "totalSubtypes", 0),
                map("totalCount", 1, "returnedCount", 1, "truncated", false,
                    "suggestedNextTools", List.of(
                        "get_type_members to see members of a specific type",
                        "find_implementations to find all implementors",
                        "find_references to see where type is used"))));
    }

    @Test
    @DisplayName("analyze_type of CompactMain: an empty hierarchy beside its members and clean diagnostics")
    void analyzeType() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "CompactMain");
        args.put("includeUsages", false);
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), new EnvelopeHarness(service), args,
            success(map(
                "type", map("name", "CompactMain", "qualifiedName", "CompactMain", "package", "(default package)",
                    "kind", "class", "modifiers", List.of("final"), "file", FILE, "line", 0),
                "members", map(
                    "constructors", List.of(),
                    "methods", List.of(
                        map("name", "main", "modifiers", List.of(), "signature", "main(): void",
                            "returnType", "void", "line", 5),
                        map("name", "message", "modifiers", List.of(), "signature", "message(): String",
                            "returnType", "String", "line", 9)),
                    "fields", List.of(map("name", "greeting", "type", "String", "modifiers", List.of(), "line", 0)),
                    "nestedTypes", List.of(),
                    "constructorCount", 0,
                    "methodCount", 2,
                    "fieldCount", 1,
                    "nestedTypeCount", 0),
                "hierarchy", map(),
                "diagnostics", map("errorCount", 0, "warningCount", 0, "hasProblems", false)),
                map("suggestedNextTools", List.of(
                    "analyze_method for detailed method analysis",
                    "find_references for specific symbol usages",
                    "get_call_hierarchy_incoming for method callers"))));
    }
}
