package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.NestedTypes.NESTED;
import static org.javalens.mcp.tools.unnamed.NestedTypes.OUTER;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.without;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract_interface adds the implements clause to the declaration of the class at the position
 * wherever that class is declared: a member class, a member record and a local class each get
 * the clause, as a top-level class does, and a class named at the position but declared in
 * another file gets it in that file. JDT's extract interface is available for each of them
 * (RefactoringAvailabilityTesterCore.isExtractInterfaceAvailable refuses only annotations,
 * anonymous classes, lambdas, and binary or read-only types); a library class, which has no
 * source, is refused.
 *
 * <p>Outer.java (nested-types): the inner class {@code Inner} (line 4, name from column 10 to
 * 15) declares the public method {@code descend()}; the member record {@code Point} (line 17,
 * name at column 11, its component list closing at column 30) declares the public method
 * {@code int sum()}; the local class {@code Step} in {@code task()} (line 24, name from column
 * 14 to 18) declares the public method {@code advance()}. Each new interface is a top-level
 * public interface in com.example.nested, beside Outer.java.
 */
class ExtractInterfaceNestedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String outer;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
        outer = helper.getFixturePath(NestedTypes.FIXTURE).resolve(OUTER).toString();
    }

    @Test
    @DisplayName("extract_interface of the inner class Inner adds implements Descending right after its name")
    void memberClass() {
        assertExtraction(4, 10, "Inner", "Descending", "descend", "void descend()", 15);
    }

    @Test
    @DisplayName("extract_interface of the member record Point adds implements Summing right after its component list")
    void memberRecord() {
        assertExtraction(17, 11, "Point", "Summing", "sum", "int sum()", 30);
    }

    @Test
    @DisplayName("extract_interface of the local class Step adds implements Advancing right after its name")
    void localClass() {
        assertExtraction(24, 14, "Step", "Advancing", "advance", "void advance()", 18);
    }

    @Test
    @DisplayName("extract_interface at the reference to Shelf in Graph's extends clause adds implements Sized to Shelf's own declaration in Shelf.java")
    void classDeclaredInAnotherFile() {
        // Graph.java line 6 names Shelf from column 27. Shelf (Shelf.java, line 2, name ending
        // at column 18) declares the public constructor Shelf() and the public method
        // capacity(); a constructor is never extracted.
        Map<String, Object> data = map(
            "className", "Shelf",
            "interfaceName", "Sized",
            "packageName", "com.example.nested",
            "interfaceFilePath", NESTED + "Sized.java",
            "interfaceContent", "package com.example.nested;\n\npublic interface Sized {\n\n    int capacity();\n\n}\n",
            "extractedMethods", List.of(map("name", "capacity", "signature", "int capacity()")),
            "classEdits", List.of(map("type", "insert", "line", 2, "column", 18, "newText", " implements Sized")),
            "sourceFilePath", NESTED + "Shelf.java");
        String graph = helper.getFixturePath(NestedTypes.FIXTURE).resolve(NESTED + "Graph.java").toString();
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, args(graph, 6, 27, "Sized"),
            success(data, meta()), without("offset", "data", "classEdits"));
    }

    @Test
    @DisplayName("extract_interface at a reference to the library class Object is INVALID_PARAMETER: the class has no source to take an implements clause")
    void libraryClass() {
        // Flows.java line 22 declares Object slot; Object is named from column 8.
        String flows = helper.getFixturePath(NestedTypes.FIXTURE).resolve(NestedTypes.FLOWS).toString();
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, args(flows, 22, 8, "Objective"),
            UnnamedElements.invalidParameter("Invalid parameter 'position': Class at position is the library class "
                + "java.lang.Object, which has no source to add an implements clause to"));
    }

    @Test
    @DisplayName("extract_interface in the implicitly declared class Greeter is INVALID_PARAMETER: it has no class declaration to take an implements clause")
    void implicitlyDeclaredClass() throws Exception {
        // Greeter.java (implicit-class, a Java 25 compact source file) declares the public
        // method greet(), its name at line 2, column 14, in the class declared implicitly for
        // the file, which JDT names after it; that class has no declaration of its own, so no
        // implements clause (JLS 7.3).
        JdtServiceImpl compact = helper.loadProject("implicit-class");
        String greeter = helper.getFixturePath("implicit-class").resolve("src/main/java/Greeter.java").toString();
        assertPayload(mapper, new ExtractInterfaceTool(() -> compact), new EnvelopeHarness(compact),
            args(greeter, 2, 14, "Greeting"),
            UnnamedElements.invalidParameter("Invalid parameter 'position': Class at position is the implicitly "
                + "declared class Greeter, which cannot declare an implements clause"));
    }

    @Test
    @DisplayName("the three interfaces created and their classEdits applied compile, and each class implements its interface")
    void appliedExtractionsCompile() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(NestedTypes.FIXTURE);
        Path root = helper.getTempDirectory().resolve(NestedTypes.FIXTURE);
        Path file = root.resolve(OUTER);
        ExtractInterfaceTool tool = new ExtractInterfaceTool(() -> copy);
        List<Map<String, Object>> edits = new ArrayList<>();
        edits.addAll(extraction(tool, root, file, 4, 10, "Descending"));
        edits.addAll(extraction(tool, root, file, 17, 11, "Summing"));
        edits.addAll(extraction(tool, root, file, 24, 14, "Advancing"));
        Files.writeString(file, NestedTypes.applied(Files.readString(file, StandardCharsets.UTF_8), edits),
            StandardCharsets.UTF_8);

        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        IType outerType = reloaded.getCompilationUnit(file).getType("Outer");
        assertAll(
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, file), "errors in Outer.java"),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(NESTED + "Descending.java"))),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(NESTED + "Summing.java"))),
            () -> assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(NESTED + "Advancing.java"))),
            () -> assertEquals(List.of("Descending"), List.of(outerType.getType("Inner").getSuperInterfaceNames())),
            () -> assertEquals(List.of("Summing"), List.of(outerType.getType("Point").getSuperInterfaceNames())),
            () -> assertEquals(List.of("Advancing"),
                List.of(outerType.getMethod("task", new String[0]).getType("Step", 1).getSuperInterfaceNames())));
    }

    private void assertExtraction(int line, int column, String className, String interfaceName, String method,
                                  String signature, int clauseColumn) {
        Map<String, Object> data = map(
            "className", className,
            "interfaceName", interfaceName,
            "packageName", "com.example.nested",
            "interfaceFilePath", NESTED + interfaceName + ".java",
            "interfaceContent", "package com.example.nested;\n\npublic interface " + interfaceName + " {\n\n    "
                + signature + ";\n\n}\n",
            "extractedMethods", List.of(map("name", method, "signature", signature)),
            "classEdits", List.of(map("type", "insert", "line", line, "column", clauseColumn,
                "newText", " implements " + interfaceName)),
            "sourceFilePath", OUTER);
        assertPayload(mapper, new ExtractInterfaceTool(() -> service), envelope, args(outer, line, column, interfaceName),
            success(data, meta()), without("offset", "data", "classEdits"));
    }

    private static Map<String, Object> meta() {
        return map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "Create the interface file with the provided content",
                "Apply the classEdits to add implements clause",
                "get_diagnostics to verify no errors"));
    }

    /** The classEdits of one extraction from the copy, after writing the interface it creates. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extraction(ExtractInterfaceTool tool, Path root, Path file, int line, int column,
                                                 String interfaceName) throws Exception {
        ToolResponse response = tool.execute(args(file.toString(), line, column, interfaceName));
        assertTrue(response.isSuccess(), () -> "extract_interface failed: " + response.getError());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        Files.writeString(root.resolve((String) data.get("interfaceFilePath")), (String) data.get("interfaceContent"),
            StandardCharsets.UTF_8);
        return (List<Map<String, Object>>) data.get("classEdits");
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
