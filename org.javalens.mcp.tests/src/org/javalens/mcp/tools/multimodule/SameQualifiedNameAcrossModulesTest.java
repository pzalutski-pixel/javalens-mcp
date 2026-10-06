package org.javalens.mcp.tools.multimodule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file-path tool in a multi-module project where two modules hold the same qualified
 * name answers for exactly the file the path names, never for the other module's type of
 * that name. The test writes a Maven reactor (root pom with modules {@code modA} and
 * {@code modB}, each holding {@code com.example.Util} with a different method, plus a copy
 * of {@code Util.java} under a directory that is no module) and loads it through
 * {@code load_project}.
 *
 * <p>The tool is {@code get_document_symbols}: its payload is the file's types and members
 * and nothing else, so each module's members are pinned exactly. {@code analyze_file}
 * would also carry the file's diagnostics, which here include the duplicate-type error with
 * raw source offsets.
 */
class SameQualifiedNameAcrossModulesTest {

    private static final String UTIL = "src/main/java/com/example/Util.java";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path projectRoot;
    private GetDocumentSymbolsTool tool;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = helper.getTempDirectory().resolve("same-name-reactor");
        writeFile("pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>same-name-reactor</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>
                <modules>
                    <module>modA</module>
                    <module>modB</module>
                </modules>
                <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties>
            </project>
            """);
        writeFile("modA/pom.xml", modulePom("modA"));
        writeFile("modB/pom.xml", modulePom("modB"));
        writeFile("modA/" + UTIL, """
            package com.example;

            public class Util {
                public String alpha() {
                    return "a";
                }
            }
            """);
        writeFile("modB/" + UTIL, """
            package com.example;

            public class Util {
                public int beta(int value) {
                    return value;
                }
            }
            """);
        writeFile("archive/" + UTIL, """
            package com.example;

            public class Util {
                public void archived() {
                }
            }
            """);

        AtomicReference<IJdtService> loaded = new AtomicReference<>();
        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", projectRoot.toString());
        ToolResponse load = new LoadProjectTool(loaded::set).execute(loadArgs);
        assertEquals(true, load.isSuccess(), () -> "load_project failed: " + load.getError().getMessage());
        JdtServiceImpl service = (JdtServiceImpl) loaded.get();
        // The two modules' Util.java files are the project's only linked sources.
        assertEquals(2, service.getSourceFileCount(), "linked source files");

        tool = new GetDocumentSymbolsTool(() -> service);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_document_symbols on modB's Util.java returns modB's members exactly, at both seams")
    void documentSymbolsOfModBUtilAreModBMembers() {
        Map<String, Object> expected = utilSymbols("modB/" + UTIL,
            Map.of("name", "beta", "kind", "method", "modifiers", List.of("public"),
                "signature", "beta(int value): int", "line", 3));

        assertDocumentSymbols(projectRoot.resolve("modB/" + UTIL), expected);
    }

    @Test
    @DisplayName("get_document_symbols on modA's Util.java returns modA's members exactly, at both seams")
    void documentSymbolsOfModAUtilAreModAMembers() {
        Map<String, Object> expected = utilSymbols("modA/" + UTIL,
            Map.of("name", "alpha", "kind", "method", "modifiers", List.of("public"),
                "signature", "alpha(): String", "line", 3));

        assertDocumentSymbols(projectRoot.resolve("modA/" + UTIL), expected);
    }

    @Test
    @DisplayName("get_document_symbols on a Util.java under no module's source root is FILE_NOT_FOUND, at both seams")
    void documentSymbolsOfFileUnderNoModuleIsNotFound() {
        String archived = projectRoot.resolve("archive/" + UTIL).toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", archived);

        ToolResponse direct = tool.execute(args);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_document_symbols", args);

        // Code and message of each seam's error, or null when it reports none.
        List<String> directError = direct.getError() == null ? null
            : List.of(direct.getError().getCode(), direct.getError().getMessage());
        JsonNode error = payload.path("error");
        List<String> envelopeError = error.isObject()
            ? List.of(error.path("code").asText(), error.path("message").asText())
            : null;
        List<String> expectedError = List.of(ErrorInfo.FILE_NOT_FOUND, "File not found: " + archived);
        assertAll(
            () -> assertEquals(false, direct.isSuccess(), "execute(): success"),
            () -> assertEquals(expectedError, directError, "execute(): error code and message"),
            () -> assertEquals(false, payload.get("success").asBoolean(), "envelope: success"),
            () -> assertEquals(expectedError, envelopeError, "envelope: error code and message"));
    }

    /** Run get_document_symbols on {@code file} through execute() and the MCP envelope. */
    private void assertDocumentSymbols(Path file, Map<String, Object> expected) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());

        ToolResponse direct = tool.execute(args);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_document_symbols", args);

        assertAll(
            () -> assertEquals(true, direct.isSuccess(), "execute(): success"),
            () -> assertEquals(expected, direct.getData(), "execute(): data"),
            () -> assertEquals(true, payload.get("success").asBoolean(), "envelope: success"),
            () -> assertEquals(objectMapper.valueToTree(expected), payload.get("data"), "envelope: data"));
    }

    /**
     * The get_document_symbols data of a Util.java as written here: the public class Util on
     * line 2 with {@code method} as its only member, two symbols in all.
     */
    private static Map<String, Object> utilSymbols(String file, Map<String, Object> method) {
        Map<String, Object> util = Map.of("name", "Util", "kind", "class", "modifiers", List.of("public"),
            "line", 2, "children", List.of(method));
        return Map.of("file", file, "symbols", List.of(util), "totalSymbols", 2);
    }

    private static String modulePom(String artifactId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>same-name-reactor</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>%s</artifactId>
            </project>
            """.formatted(artifactId);
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
