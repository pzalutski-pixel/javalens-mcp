package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins repair idempotence and edit sequences: a repair settles the stamps so
 * the next query is a fast-path no-op; repeated edits to the same file are
 * each picked up; a delete followed by a re-add with different content lands
 * on the new content; consecutive different tools share one repair.
 */
class DiskSyncSequenceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectCopy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        projectCopy = service.getProjectRoot();
        objectMapper = new ObjectMapper();
    }

    private Path calculator() {
        return projectCopy.resolve("src/main/java/com/example/Calculator.java");
    }

    private ObjectNode fileArgs(Path file) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("includeMembers", true);
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<String> methodNames(ToolResponse r) {
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        List<Map<String, Object>> methods = (List<Map<String, Object>>) types.get(0).get("methods");
        return methods.stream().map(m -> (String) m.get("name")).toList();
    }

    private void appendMethod(Path file, String methodName) throws Exception {
        String source = Files.readString(file);
        int lastBrace = source.lastIndexOf('}');
        Files.writeString(file, source.substring(0, lastBrace)
            + "\n    public void " + methodName + "() {\n    }\n}\n");
    }

    @Test
    @DisplayName("a repair settles the stamps: the next verify is an empty fast path")
    void repairIsIdempotent() throws Exception {
        appendMethod(calculator(), "freshlyAdded");

        ToolResponse r = new AnalyzeFileTool(() -> service).execute(fileArgs(calculator()));
        assertTrue(r.isSuccess());
        assertTrue(methodNames(r).contains("freshlyAdded"), "first query repairs and sees the edit");

        assertEquals(List.of(), service.ensureFresh(),
            "after the repair, verify must find nothing to do");
    }

    @Test
    @DisplayName("repeated edits to the same file are each picked up")
    void editQueryEditQuery() throws Exception {
        AnalyzeFileTool analyze = new AnalyzeFileTool(() -> service);

        appendMethod(calculator(), "firstEdit");
        assertTrue(methodNames(analyze.execute(fileArgs(calculator()))).contains("firstEdit"));

        appendMethod(calculator(), "secondEdit");
        List<String> names = methodNames(analyze.execute(fileArgs(calculator())));
        assertTrue(names.contains("firstEdit") && names.contains("secondEdit"),
            () -> "both edits must be visible; got: " + names);
    }

    @Test
    @DisplayName("delete then re-add with different content lands on the new content")
    void deleteThenReAdd() throws Exception {
        Path greeter = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        Files.delete(greeter);

        // Observe the delete through any query.
        assertTrue(new GetDiagnosticsTool(() -> service).execute(fileArgs(calculator())).isSuccess());

        Files.writeString(greeter, """
            package com.example;

            public class Greeter {

                public String reborn() {
                    return "back";
                }
            }
            """);

        List<String> names = methodNames(new AnalyzeFileTool(() -> service).execute(fileArgs(greeter)));
        assertEquals(List.of("reborn"), names,
            "the re-added file's NEW content must be what the model sees");
    }

    @Test
    @DisplayName("two different tools back-to-back share one repair")
    void twoToolsShareOneRepair() throws Exception {
        appendMethod(calculator(), "sharedRepair");

        assertTrue(new FindReferencesTool(() -> service).execute(positionArgs()).isSuccess());
        assertEquals(List.of(), service.ensureFresh(),
            "the second tool call must hit the fast path - the first already repaired");

        ToolResponse second = new AnalyzeFileTool(() -> service).execute(fileArgs(calculator()));
        assertTrue(methodNames(second).contains("sharedRepair"));
    }

    private ObjectNode positionArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculator().toString());
        args.put("line", 14);
        args.put("column", 16);
        return args;
    }
}
