package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.SemanticAssertions;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FindReferencesTool.
 * Tests finding references across files.
 */
class FindReferencesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReferencesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReferencesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getReferences(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("locations");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type references returns symbol info, totalCount, and reference locations")
    void typeReferences_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Symbol info — exact
        assertEquals("Calculator", data.get("symbol"));
        assertEquals("class", data.get("symbolKind"));
        int totalCount = ((Number) data.get("totalCount")).intValue();
        assertTrue(totalCount > 0, "Calculator is referenced in fixtures; totalCount must be > 0");

        // References list with location details
        List<Map<String, Object>> references = getReferences(data);
        assertNotNull(references, "references must be present");
        assertEquals(totalCount, references.size(),
            "totalCount must equal references.size(); got: total=" + totalCount + " size=" + references.size());
        assertTrue(references.stream().anyMatch(ref ->
            ref.get("filePath") != null &&
            ref.get("filePath").toString().contains("UserService")),
            "Calculator must be referenced in UserService.java; got: " + references);

        // Each reference entry has a valid filePath, line, column
        Map<String, Object> ref = references.get(0);
        String filePath = (String) ref.get("filePath");
        assertNotNull(filePath, "ref filePath missing: " + ref);
        assertTrue(filePath.endsWith(".java"), "filePath must point to a .java file; got: " + ref);
        assertTrue(((Number) ref.get("line")).intValue() >= 0, "line must be >= 0; got: " + ref);
        assertTrue(((Number) ref.get("column")).intValue() >= 0, "column must be >= 0; got: " + ref);
    }

    @Test
    @DisplayName("Method references returns symbol with containingType")
    void methodReferences_returnsSymbolWithContainingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("method", data.get("symbolKind"));
        assertEquals("Calculator", data.get("containingType"));
        assertNotNull(getReferences(data));
    }

    @Test
    @DisplayName("Field references finds usages across methods")
    void fieldReferences_findsUsagesAcrossMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("symbol"));
        assertEquals("field", data.get("symbolKind"));

        List<Map<String, Object>> references = getReferences(data);
        assertNotNull(references);
        assertFalse(references.isEmpty());
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("maxResults limits number of references returned")
    void maxResults_limitsReferences() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> references = getReferences(data);
        assertTrue(references.size() <= 1);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 5);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Symbol with no external references returns empty or minimal list")
    void symbolWithNoReferences_returnsEmptyList() {
        String helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(getReferences(data));
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    private String fixturePath(String relative) {
        return projectPath.resolve(relative).toString();
    }

    private ObjectNode argsAt(String filePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 1000);
        return args;
    }

    @Test
    @DisplayName("references to Animal type appear in Dog, FieldHolder, and WidgetHelper files")
    void animalType_referencesAppearInExpectedFiles() {
        // Animal.java line 5 (0-based) is `public class Animal {`
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Animal.java"), 5, 13));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        assertEquals("Animal", data.get("symbol"));
        assertEquals("class", data.get("symbolKind"));

        List<Map<String, Object>> references = getReferences(data);
        Set<String> referencingFiles = references.stream()
            .map(ref -> (String) ref.get("filePath"))
            .map(p -> p == null ? "" : p.replace('\\', '/'))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .collect(Collectors.toSet());

        // Animal is referenced by Dog (extends), FieldHolder (field/param/return),
        // and WidgetHelper (param/return). Exact filenames must appear.
        assertTrue(referencingFiles.contains("Animal.java"),
            "Dog extends Animal inside Animal.java; got: " + referencingFiles);
        assertTrue(referencingFiles.contains("FieldHolder.java"),
            "FieldHolder uses Animal as field, ctor param, and return type; got: " + referencingFiles);
        assertTrue(referencingFiles.contains("WidgetHelper.java"),
            "WidgetHelper uses Animal as ctor param and return type; got: " + referencingFiles);
    }

    @Test
    @DisplayName("references to FieldHolder.pet field appear in declaring file and cross-file")
    void fieldHolderPet_referencesAcrossFiles() {
        // FieldHolder.java line 4 (0-based) is `    Animal pet;` — field "pet" starts at column 11
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/FieldHolder.java"), 4, 11));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        assertEquals("pet", data.get("symbol"));
        assertEquals("field", data.get("symbolKind"));
        assertEquals("FieldHolder", data.get("containingType"));

        List<Map<String, Object>> references = getReferences(data);
        Set<String> referencingFiles = references.stream()
            .map(ref -> (String) ref.get("filePath"))
            .map(p -> p == null ? "" : p.replace('\\', '/'))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .collect(Collectors.toSet());

        assertTrue(referencingFiles.contains("FieldHolder.java"),
            "FieldHolder.pet referenced inside FieldHolder.java (constructors + getPet); got: "
                + referencingFiles);
        assertTrue(referencingFiles.contains("WidgetHelper.java"),
            "FieldHolder.pet referenced in WidgetHelper.java (describe, swap, extract); got: "
                + referencingFiles);
    }

    @Test
    @DisplayName("references to a field local to a class (Calculator.lastResult) are confined to declaring file")
    void calculatorLastResult_referencesConfinedToDeclaringFile() {
        // Calculator.java line 6 (0-based) is `    private int lastResult;`
        ToolResponse r = tool.execute(argsAt(calculatorPath, 6, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        assertEquals("lastResult", data.get("symbol"));

        List<Map<String, Object>> references = getReferences(data);
        Set<String> referencingFiles = references.stream()
            .map(ref -> (String) ref.get("filePath"))
            .map(p -> p == null ? "" : p.replace('\\', '/'))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .collect(Collectors.toSet());

        // Calculator.lastResult is private; only referenced from Calculator.java
        assertEquals(Set.of("Calculator.java"), referencingFiles,
            "Private field lastResult must only appear in Calculator.java; got: " + referencingFiles);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Reference info: context line present, line + column populated")
    @SuppressWarnings("unchecked")
    void referenceInfo_includesContextAndLocation() {
        // Calculator.add — known cross-file references.
        ToolResponse r = tool.execute(argsAt(calculatorPath, 14, 15));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> refs = getReferences(data);
        assertFalse(refs.isEmpty());
        Map<String, Object> ref = refs.get(0);
        String context = (String) ref.get("context");
        assertNotNull(context, "Reference info must include the context line; got: " + ref);
        assertFalse(context.isBlank(), "context must be non-blank source line; got: " + ref);
        // For Calculator.add references, the context should literally include "add(" somewhere.
        assertTrue(context.contains("add"),
            "context for a Calculator.add reference must mention `add`; got: " + ref);
        assertTrue(((Number) ref.get("line")).intValue() >= 0, "line must be >= 0; got: " + ref);
        assertTrue(((Number) ref.get("column")).intValue() >= 0, "column must be >= 0; got: " + ref);
    }

    @Test
    @DisplayName("referenceKind=METHOD_INVOCATION for method references")
    @SuppressWarnings("unchecked")
    void referenceKind_methodInvocation() {
        ToolResponse r = tool.execute(argsAt(calculatorPath, 14, 15));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> refs = getReferences(data);
        for (Map<String, Object> ref : refs) {
            assertEquals("METHOD_INVOCATION", ref.get("referenceKind"),
                "Every Calculator.add reference must have referenceKind=METHOD_INVOCATION; got: " + ref);
        }
    }

    @Test
    @DisplayName("referenceKind=TYPE_REFERENCE for type references")
    @SuppressWarnings("unchecked")
    void referenceKind_typeReference() {
        // Calculator type position — references are type uses across files.
        // Javadoc @see / {@link} references are correctly classified as JAVADOC by the
        // tool (match.isInsideDocComment() short-circuit); exclude them when asserting
        // the source-code classification.
        ToolResponse r = tool.execute(argsAt(calculatorPath, 5, 13));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> refs = getReferences(data);
        for (Map<String, Object> ref : refs) {
            if ("JAVADOC".equals(ref.get("referenceKind"))) continue;
            assertEquals("TYPE_REFERENCE", ref.get("referenceKind"),
                "Every Calculator type reference must have referenceKind=TYPE_REFERENCE; got: " + ref);
        }
    }

    @Test
    @DisplayName("referenceKind=FIELD_ACCESS for field references")
    @SuppressWarnings("unchecked")
    void referenceKind_fieldAccess() {
        // Calculator.lastResult field — only used inside Calculator.java.
        // Javadoc references would also be classified as JAVADOC by the tool — skip
        // those when asserting the source-code classification.
        ToolResponse r = tool.execute(argsAt(calculatorPath, 6, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> refs = getReferences(data);
        for (Map<String, Object> ref : refs) {
            if ("JAVADOC".equals(ref.get("referenceKind"))) continue;
            assertEquals("FIELD_ACCESS", ref.get("referenceKind"),
                "Every lastResult reference must have referenceKind=FIELD_ACCESS; got: " + ref);
        }
    }

    @Test
    @DisplayName("Class-level type parameter <T extends Number> finds usages in member signatures")
    @SuppressWarnings("unchecked")
    void classTypeParameter_findsUsagesInMethods() {
        // GenericInterfaceExtractTarget<T extends Number> uses T in field, ctor,
        // get(), set(T value), and pair()'s return List<T>. Positioning on the
        // declaration of T must resolve to ITypeParameter and find those usages.
        String generic = projectPath.resolve(
            "src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
            int idx = source.indexOf("GenericInterfaceExtractTarget<T");
            int tIdx = source.indexOf("T", idx + "GenericInterfaceExtractTarget<".length() - 1);
            tIdx = source.indexOf("T extends", idx);
            int line = (int) source.substring(0, tIdx).chars().filter(c -> c == '\n').count();
            int lineStart = source.lastIndexOf('\n', tIdx) + 1;
            int column = tIdx - lineStart;

            ToolResponse r = tool.execute(argsAt(generic, line, column));
            assertTrue(r.isSuccess(),
                "Position on class type parameter T must resolve; got: "
                    + (r.getError() != null ? r.getError().getMessage() : "n/a"));
            Map<String, Object> data = getData(r);
            assertEquals("T", data.get("symbol"));
            assertEquals("typeParameter", data.get("symbolKind"));

            List<Map<String, Object>> refs = getReferences(data);
            assertFalse(refs.isEmpty(),
                "Type parameter T is used in field declaration, constructor parameter, "
                    + "get(), set(T value), and pair() return; references must be non-empty");
            for (Map<String, Object> ref : refs) {
                assertEquals("TYPE_PARAMETER_REFERENCE", ref.get("referenceKind"),
                    "All T references must classify as TYPE_PARAMETER_REFERENCE; got: " + ref);
            }
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("Generic-class member reference: find_references on GenericClass.read() surfaces cross-file caller")
    @SuppressWarnings("unchecked")
    void genericClassMember_findsCrossFileCaller() {
        // GenericClass<T>.read() returns T. GenericClassConsumer calls
        // container.read() on a GenericClass<String>. find_references on
        // the read() method declaration must surface that call site.
        // (find_references uses SearchEngine — index-driven, unaffected by
        // the IBinding equality quirk that B1-1 fixed.)
        String generic = projectPath.resolve(
            "src/main/java/com/example/genericunused/GenericClass.java").toString();
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
            int idx = source.indexOf("read()");
            int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
            int col = idx - (source.lastIndexOf('\n', idx) + 1);

            ToolResponse r = tool.execute(argsAt(generic, line, col));
            assertTrue(r.isSuccess());
            Map<String, Object> data = getData(r);
            assertEquals("read", data.get("symbol"));
            assertEquals("method", data.get("symbolKind"));

            List<Map<String, Object>> refs = getReferences(data);
            boolean consumerFound = refs.stream()
                .map(ref -> (String) ref.get("filePath"))
                .filter(java.util.Objects::nonNull)
                .map(p -> p.replace('\\', '/'))
                .anyMatch(p -> p.endsWith("GenericClassConsumer.java"));
            assertTrue(consumerFound,
                "GenericClassConsumer calls container.read() — must appear; got: " + refs);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("Constructor position resolves and finds constructor references")
    @SuppressWarnings("unchecked")
    void constructorPosition_findsConstructorReferences() throws Exception {
        String ctPath = projectPath.resolve("src/main/java/com/example/ConstructorTarget.java").toString();
        // ConstructorTarget(String name, int count) — 2-arg constructor declaration.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(ctPath));
        int idx = source.indexOf("public ConstructorTarget(String name, int count)");
        idx = source.indexOf("ConstructorTarget(", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", ctPath);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on constructor name must resolve; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("locations");
        Set<String> filenames = refs.stream()
            .map(rr -> (String) rr.get("filePath"))
            .map(p -> p == null ? "" : p.replace('\\', '/'))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .collect(Collectors.toSet());
        assertTrue(filenames.contains("ConstructorCaller.java"),
            "ConstructorCaller uses the 2-arg ConstructorTarget constructor — must appear; got: "
                + filenames);
    }
}
