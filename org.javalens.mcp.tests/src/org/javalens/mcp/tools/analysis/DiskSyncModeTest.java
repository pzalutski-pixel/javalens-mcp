package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.sync.DiskSyncMode;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.HealthCheckTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the disk-sync mode contract at the tool chokepoint: under strict
 * (default), every tool call verifies and repairs before answering; under
 * manual, behavior is exactly pre-1.5.0 (the AI drives sync). Build-file
 * changes surface as RELOAD_REQUIRED, verification I/O failures as
 * VERIFICATION_FAILED - never a silent or unverified answer.
 */
class DiskSyncModeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectCopy;
    private FindReferencesTool findReferences;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        projectCopy = service.getProjectRoot();
        findReferences = new FindReferencesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    private ObjectNode addPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectCopy.resolve("src/main/java/com/example/Calculator.java").toString());
        args.put("line", 14);
        args.put("column", 16);
        return args;
    }

    @SuppressWarnings("unchecked")
    private boolean referencesContainUserService(ToolResponse r) {
        List<Map<String, Object>> locations =
            (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("locations");
        return locations.stream()
            .map(l -> String.valueOf(l.get("filePath")).replace('\\', '/'))
            .anyMatch(p -> p.endsWith("UserService.java"));
    }

    // ========== strict (default) ==========

    @Test
    @DisplayName("strict is the default mode")
    void strictIsDefault() {
        assertEquals(DiskSyncMode.STRICT, service.getDiskSyncMode());
    }

    @Test
    @DisplayName("strict: a deleted caller disappears from the very next query, no reload")
    void strict_editVisibleWithoutReload() throws IOException {
        ToolResponse before = findReferences.execute(addPosition());
        assertTrue(before.isSuccess());
        assertTrue(referencesContainUserService(before),
            "UserService calls Calculator.add and must appear before the delete");

        Files.delete(projectCopy.resolve("src/main/java/com/example/service/UserService.java"));

        ToolResponse after = findReferences.execute(addPosition());
        assertTrue(after.isSuccess(), () -> "expected success; got: " + after.getError());
        assertFalse(referencesContainUserService(after),
            "strict mode must reflect the delete on the next query without any reload");
    }

    // ========== manual ==========

    @Test
    @DisplayName("manual: pre-1.5.0 behavior exactly - a new call site stays invisible until reload")
    void manual_staleAnswerPreserved() throws IOException {
        service.setDiskSyncMode(DiskSyncMode.MANUAL);

        ToolResponse before = findReferences.execute(addPosition());
        assertTrue(before.isSuccess());
        int sitesBefore = referenceCount(before);

        Path greeter = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        String source = Files.readString(greeter);
        int lastBrace = source.lastIndexOf('}');
        Files.writeString(greeter, source.substring(0, lastBrace)
            + "\n    public int viaManual() {\n        return new Calculator().add(3, 4);\n    }\n}\n");

        ToolResponse after = findReferences.execute(addPosition());
        assertTrue(after.isSuccess());
        assertEquals(sitesBefore, referenceCount(after),
            "manual mode must NOT auto-repair - the new call site stays invisible, exactly as before 1.5.0");
    }

    @SuppressWarnings("unchecked")
    private int referenceCount(ToolResponse r) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("locations")).size();
    }

    // ========== RELOAD_REQUIRED ==========

    @Test
    @DisplayName("a build-file change fails loud with RELOAD_REQUIRED naming the file")
    void buildFileChange_reloadRequired() throws IOException {
        Path pom = projectCopy.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom)
            .replace("</project>", "    <!-- touched -->\n</project>"));

        ToolResponse r = findReferences.execute(addPosition());
        assertFalse(r.isSuccess());
        assertEquals("RELOAD_REQUIRED", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("pom.xml"),
            () -> "message must name the changed build file; got: " + r.getError().getMessage());
        assertNull(r.getData(), "error responses carry no data");
    }

    @Test
    @DisplayName("verification precedes parameter validation: RELOAD_REQUIRED wins over missing params")
    void reloadRequired_beatsParamValidation() throws IOException {
        Path pom = projectCopy.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom)
            .replace("</project>", "    <!-- touched -->\n</project>"));

        ToolResponse r = findReferences.execute(objectMapper.createObjectNode());
        assertEquals("RELOAD_REQUIRED", r.getError().getCode(),
            "an untrustworthy model must be reported before any argument is interpreted");
    }

    // ========== VERIFICATION_FAILED ==========

    @Test
    @DisplayName("a structural verification failure fails loud with VERIFICATION_FAILED")
    void structuralFailure_verificationFailed() throws IOException {
        Path testRoot = projectCopy.resolve("src/test/java");
        Files.walk(testRoot)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        ToolResponse r = findReferences.execute(addPosition());
        assertFalse(r.isSuccess());
        assertEquals("VERIFICATION_FAILED", r.getError().getCode());
        assertNull(r.getData(), "an unverified answer must never be returned");
    }

    // ========== health_check visibility ==========

    @Test
    @DisplayName("health_check reports the active diskSync mode")
    void healthCheck_reportsMode() {
        HealthCheckTool health = new HealthCheckTool(() -> service);

        @SuppressWarnings("unchecked")
        Map<String, Object> strictData = (Map<String, Object>) health.execute(objectMapper.createObjectNode()).getData();
        assertEquals("strict", strictData.get("diskSync"));

        service.setDiskSyncMode(DiskSyncMode.MANUAL);
        @SuppressWarnings("unchecked")
        Map<String, Object> manualData = (Map<String, Object>) health.execute(objectMapper.createObjectNode()).getData();
        assertEquals("manual", manualData.get("diskSync"));
    }
}
