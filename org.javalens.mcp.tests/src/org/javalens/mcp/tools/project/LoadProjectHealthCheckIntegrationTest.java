package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.Tool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the load_project -> health_check state seam against the REAL
 * {@code JavaLensApplication.registerTools()} wiring. The earlier protocol
 * tests used replica registrations (a test-local callback that only stores
 * the service), so they structurally could not catch the production wiring
 * bug where loading a project via the tool left health_check reporting "not
 * loaded" (the loading state was flipped only by the JAVA_PROJECT_PATH
 * auto-load path). This test exercises the actual registered tools.
 *
 * <p>Once a project is loaded, health_check reports the same {@code buildModel} summary
 * and the same {@code warnings} that load_project reported, both through execute() and
 * through the JSON-RPC envelope.
 */
class LoadProjectHealthCheckIntegrationTest {

    private static final String MAVEN_BINARY_PROPERTY = "javalens.maven.binary";

    /**
     * simple-maven's build model as Maven reports it: the pom declares
     * maven.compiler.source 21 and no modules, so the root, com.example:simple-maven, is
     * the one module.
     */
    private static final Map<String, Object> SIMPLE_MAVEN_BUILD_MODEL = Map.of(
        "buildSystem", "maven",
        "source", "build-tool",
        "analysisLevel", "21",
        "moduleCount", 1,
        "modules", List.of(Map.of("id", "com.example:simple-maven", "dir", ".")));

    /**
     * The build model of {@link #writeProjectWithMissingModule}, read from the poms because
     * Maven cannot start: the root and {@code present} load, {@code ghost} does not, and the
     * root pom declares maven.compiler.release 17.
     */
    private static final Map<String, Object> MISSING_MODULE_BUILD_MODEL = Map.of(
        "buildSystem", "maven",
        "source", "static-read",
        "analysisLevel", "17",
        "moduleCount", 2,
        "modules", List.of(
            Map.of("id", "com.example:missing-module-parent", "dir", "."),
            Map.of("id", "com.example:present", "dir", "present")));

    /** The root pom's {@code <module>ghost</module>}, whose directory does not exist. */
    private static final Map<String, Object> GHOST_WARNING = Map.of(
        "code", LoadWarning.MODULE_NOT_LOADED,
        "message", "pom.xml declares 'ghost', but its directory ghost does not exist",
        "remediation", "Create the directory or remove the declaration from pom.xml.",
        "module", "ghost");

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ToolRegistry registry;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String savedMavenBinary;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        savedMavenBinary = System.getProperty(MAVEN_BINARY_PROPERTY);

        // Build the app and run its REAL registerTools() so the load_project
        // tool carries its production callback (the place the bug lived).
        JavaLensApplication app = new JavaLensApplication();
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        ToolRegistry r = new ToolRegistry();
        registryField.set(app, r);
        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        registry = r;
    }

    @AfterEach
    void restoreMavenBinary() {
        if (savedMavenBinary == null) {
            System.clearProperty(MAVEN_BINARY_PROPERTY);
        } else {
            System.setProperty(MAVEN_BINARY_PROPERTY, savedMavenBinary);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ToolResponse call(String toolName, ObjectNode args) {
        Tool tool = registry.getTool(toolName).orElseThrow();
        return tool.execute(args);
    }

    @Test
    @DisplayName("health_check reports not-loaded before any load")
    void healthCheck_beforeLoad_notLoaded() {
        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) getData(health).get("project");
        assertEquals(false, project.get("loaded"));
    }

    @Test
    @DisplayName("health_check reports LOADED after a successful load_project tool call, with the load's buildModel and no warnings")
    void healthCheck_afterToolLoad_loaded() {
        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", projectPath.toString());
        ToolResponse load = call("load_project", loadArgs);
        assertTrue(load.isSuccess(), () -> "load_project failed: " + load.getError());

        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        Map<String, Object> data = getData(health);
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");

        assertEquals(true, project.get("loaded"),
            "loading a project via the tool must flip health_check to loaded (issue #30 thread)");
        assertEquals("Ready", data.get("status"));
        assertEquals("loaded", project.get("status"));
        assertEquals("strict", data.get("diskSync"), "the live service's disk-sync mode is reported");

        // A clean load: neither tool carries a 'warnings' field.
        Map<String, Object> loadData = getData(load);
        assertAll(
            () -> assertEquals(SIMPLE_MAVEN_BUILD_MODEL, loadData.get("buildModel"), "load_project buildModel"),
            () -> assertEquals(false, loadData.containsKey("warnings"), "load_project carries no warnings"),
            () -> assertEquals(Map.of(
                    "status", "loaded",
                    "loaded", true,
                    "message", "Project loaded successfully",
                    "buildModel", SIMPLE_MAVEN_BUILD_MODEL),
                project, "health_check project"),
            () -> assertEquals(List.of("status", "loaded", "message", "buildModel"),
                List.copyOf(project.keySet()), "health_check project fields in order"));
    }

    @Test
    @DisplayName("After a load that leaves a declared module unloaded, health_check reports the same buildModel and warnings as load_project")
    void healthCheck_afterDegradedLoad_reportsLoadWarnings() throws Exception {
        Path root = writeProjectWithMissingModule();
        String mvnCmd = failMavenFast();

        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", root.toString());
        ToolResponse load = call("load_project", loadArgs);
        assertTrue(load.isSuccess(), () -> "load_project failed: " + load.getError());
        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());

        assertMissingModuleReport(
            objectMapper.valueToTree(load.getData()),
            objectMapper.valueToTree(getData(health).get("project")),
            mvnCmd);
    }

    // ========== MCP envelope seam: load_project -> health_check through processMessage ==========

    @Test
    @DisplayName("Through the real MCP envelope (processMessage): load_project flips health_check to loaded/Ready/strict with the load's buildModel")
    void envelope_loadThenHealthCheck_reportsLoaded() throws Exception {
        // The integration tests above drive the real registerTools() wiring but call tools
        // via execute(). This adds the missing JSON-RPC serialization layer: the same
        // load_project -> health_check transition driven through McpProtocolHandler.processMessage.
        JdtServiceImpl loaded = helper.loadProject("simple-maven");
        EnvelopeHarness envelope = new EnvelopeHarness(loaded);

        // Drive load_project through the wire so its production callback flips the load state.
        ObjectNode loadArgs = envelope.args();
        loadArgs.put("projectPath", projectPath.toString());
        JsonNode loadPayload = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        assertTrue(loadPayload.get("success").asBoolean(),
            () -> "load_project failed through the envelope: " + loadPayload);

        JsonNode healthPayload = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        assertTrue(healthPayload.get("success").asBoolean(),
            () -> "health_check failed through the envelope: " + healthPayload);
        JsonNode project = healthPayload.get("data").get("project");
        assertTrue(project.get("loaded").asBoolean(),
            "health_check must report loaded after a load_project tools/call through processMessage");
        assertEquals("Ready", healthPayload.get("data").get("status").asText());
        assertEquals("loaded", project.get("status").asText());
        assertEquals("strict", healthPayload.get("data").get("diskSync").asText(),
            "the live service's disk-sync mode must survive the JSON-RPC envelope");

        JsonNode loadData = loadPayload.get("data");
        JsonNode expectedBuildModel = envelope.mapper().valueToTree(SIMPLE_MAVEN_BUILD_MODEL);
        assertAll(
            () -> assertEquals(expectedBuildModel, loadData.get("buildModel"), "load_project buildModel"),
            () -> assertEquals(false, loadData.has("warnings"), "load_project carries no warnings"),
            () -> assertEquals(expectedBuildModel, project.get("buildModel"), "health_check project.buildModel"),
            () -> assertEquals(List.of("status", "loaded", "message", "buildModel"), fieldNames(project),
                "health_check project fields in order"));
    }

    @Test
    @DisplayName("Through the real MCP envelope: after a load that leaves a declared module unloaded, health_check reports the same buildModel and warnings as load_project")
    void envelope_afterDegradedLoad_reportsLoadWarnings() throws Exception {
        Path root = writeProjectWithMissingModule();
        String mvnCmd = failMavenFast();
        // No service yet: the server as it starts, before any load_project call.
        EnvelopeHarness envelope = new EnvelopeHarness(null);

        ObjectNode loadArgs = envelope.args();
        loadArgs.put("projectPath", root.toString());
        JsonNode loadPayload = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        assertTrue(loadPayload.get("success").asBoolean(),
            () -> "load_project failed through the envelope: " + loadPayload);
        JsonNode healthPayload = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        assertTrue(healthPayload.get("success").asBoolean(),
            () -> "health_check failed through the envelope: " + healthPayload);

        assertMissingModuleReport(loadPayload.get("data"), healthPayload.get("data").get("project"), mvnCmd);
    }

    // ========== Helpers ==========

    /**
     * Pin load_project's report of {@link #writeProjectWithMissingModule} and health_check's
     * {@code project} after it. The load's warnings are the module walk's MODULE_NOT_LOADED
     * for {@code ghost}, then Maven failing to start {@code mvnCmd}, then the static read's
     * BUILD_MODEL_STATIC.
     */
    private void assertMissingModuleReport(JsonNode loadData, JsonNode project, String mvnCmd) {
        JsonNode warnings = loadData.path("warnings");
        JsonNode subprocessWarning = warnings.path(1);
        assertAll(
            () -> assertEquals(objectMapper.valueToTree(MISSING_MODULE_BUILD_MODEL), loadData.get("buildModel"),
                "load_project buildModel"),
            () -> assertEquals(List.of(LoadWarning.MODULE_NOT_LOADED, LoadWarning.BUILD_TOOL_UNAVAILABLE,
                    LoadWarning.BUILD_MODEL_STATIC),
                warningCodes(warnings), "load_project warning codes in order"),
            () -> assertEquals(objectMapper.valueToTree(GHOST_WARNING), warnings.get(0), "ghost's warning"),
            () -> assertEquals(List.of("code", "message", "remediation", "module"), fieldNames(warnings.path(0)),
                "ghost's warning fields in order"),
            // The subprocess warning names no module, so it carries no 'module' field.
            () -> assertEquals(List.of("code", "message", "remediation"), fieldNames(subprocessWarning),
                "subprocess warning fields in order"),
            () -> assertEquals("Install Maven and ensure '" + mvnCmd + "' is on PATH for the process that "
                    + "launches JavaLens.",
                subprocessWarning.path("remediation").asText(), "subprocess warning remediation"),
            // The message ends with the OS's process-start error text, which differs per OS
            // and locale, so only the part Maven's importer composes is pinned.
            () -> assertTrue(subprocessWarning.path("message").asText().startsWith("Could not start '" + mvnCmd + "': "),
                () -> "subprocess warning message: " + subprocessWarning.path("message").asText()),
            () -> assertEquals(List.of("status", "loaded", "message", "buildModel", "warnings"), fieldNames(project),
                "health_check project fields in order"),
            () -> assertEquals("loaded", project.path("status").asText(), "health_check project status"),
            () -> assertEquals(loadData.get("buildModel"), project.get("buildModel"),
                "health_check buildModel equals load_project's"),
            () -> assertEquals(loadData.get("warnings"), project.get("warnings"),
                "health_check warnings equal load_project's"));
    }

    /**
     * A Maven aggregator whose pom declares {@code present}, which exists with its own pom
     * and one source file, and {@code ghost}, which has no directory. The root pom declares
     * maven.compiler.release 17.
     */
    private Path writeProjectWithMissingModule() throws IOException {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("missing-module-maven"));
        Files.writeString(root.resolve("pom.xml"), """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>missing-module-parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>
                <modules>
                    <module>present</module>
                    <module>ghost</module>
                </modules>
                <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties>
            </project>
            """);
        Path present = Files.createDirectories(root.resolve("present"));
        Files.writeString(present.resolve("pom.xml"), """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>missing-module-parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>present</artifactId>
            </project>
            """);
        Path packageDir = Files.createDirectories(present.resolve("src/main/java/com/example/present"));
        Files.writeString(packageDir.resolve("Present.java"),
            "package com.example.present;\n\npublic class Present {\n}\n");
        return root;
    }

    /**
     * Point Maven at a binary that does not exist, so the classpath step fails at once.
     *
     * @return the Maven command the load will try to start
     */
    private String failMavenFast() {
        String mvnCmd = helper.getTempDirectory().resolve("no-such-mvn").toString();
        System.setProperty(MAVEN_BINARY_PROPERTY, mvnCmd);
        return mvnCmd;
    }

    // ========== The time limit: load_project and health_check are never limited ==========

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> timeoutValues() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(null, null, null),
            org.junit.jupiter.params.provider.Arguments.of("30", 30, null),
            org.junit.jupiter.params.provider.Arguments.of("2", 5,
                "JAVALENS_TIMEOUT_SECONDS is \"2\", below 5: each tool call is limited to 5 seconds"),
            org.junit.jupiter.params.provider.Arguments.of("1000", 300,
                "JAVALENS_TIMEOUT_SECONDS is \"1000\", above 300: each tool call is limited to 300 seconds"),
            org.junit.jupiter.params.provider.Arguments.of("abc", null,
                "JAVALENS_TIMEOUT_SECONDS is \"abc\", which is not a whole number of seconds: tool calls have no time limit"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "JAVALENS_TIMEOUT_SECONDS={0}")
    @org.junit.jupiter.params.provider.MethodSource("timeoutValues")
    @DisplayName("load_project and health_check answer in full under deadlines that have expired, report the limit each value sets and the warning it raises at both seams, and a tool call is then limited exactly as reported")
    void timeLimit_reportedInFullByLoadProjectAndHealthCheck(String value, Integer seconds, String warningMessage)
            throws Exception {
        List<org.javalens.core.Deadline> started = new ArrayList<>();
        EnvelopeHarness envelope = new EnvelopeHarness(null, () -> {
            JdtServiceImpl service = new JdtServiceImpl();
            service.setTimeoutSetting(org.javalens.core.TimeoutSetting.of(value));
            service.setDeadlines(limit -> {
                org.javalens.core.Deadline deadline = org.javalens.core.Deadline.atCheck(limit, 0);
                started.add(deadline);
                return deadline;
            });
            return service;
        });
        Path root = helper.getFixturePath("deadline-calls");
        ObjectNode loadArgs = envelope.args();
        loadArgs.put("projectPath", root.toString());

        JsonNode load = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode health = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        List<org.javalens.core.Deadline> startedByLoadAndHealth = List.copyOf(started);
        ObjectNode metricsArgs = envelope.args();
        metricsArgs.put("filePath", root.resolve("src/main/java/com/deadline/Draft.java").toString());
        JsonNode limited = envelope.assertEnvelopeFidelity("get_complexity_metrics", metricsArgs);

        ObjectNode buildModel = objectMapper.createObjectNode();
        buildModel.put("buildSystem", "unknown");
        buildModel.put("source", "convention");
        buildModel.put("analysisLevel", String.valueOf(Runtime.version().feature()));
        buildModel.put("moduleCount", 1);
        buildModel.putArray("modules").addObject().put("id", ".").put("dir", ".");
        com.fasterxml.jackson.databind.node.ArrayNode warnings = objectMapper.createArrayNode();
        if (warningMessage != null) {
            warnings.addObject()
                .put("code", LoadWarning.TIMEOUT_SECONDS_INVALID)
                .put("message", warningMessage)
                .put("remediation", "Set JAVALENS_TIMEOUT_SECONDS to a whole number of seconds from 5 to 300, "
                    + "or unset it for no limit, then restart JavaLens.");
        }

        ObjectNode loadData = objectMapper.createObjectNode();
        loadData.put("loaded", true);
        loadData.put("projectPath", root.toAbsolutePath().normalize().toString());
        loadData.put("sourceFileCount", 8);
        loadData.put("packageCount", 1);
        loadData.putArray("packages").add("com.deadline");
        loadData.put("classpathEntryCount", 2);
        loadData.put("loadedAt", java.time.Instant.parse(load.at("/data/loadedAt").asText()).toString());
        loadData.set("buildModel", buildModel);
        if (warningMessage != null) {
            loadData.set("warnings", warnings);
        }
        ObjectNode expectedLoad = objectMapper.createObjectNode().put("success", true);
        expectedLoad.set("data", loadData);

        ObjectNode project = objectMapper.createObjectNode();
        project.put("status", "loaded");
        project.put("loaded", true);
        project.put("message", "Project loaded successfully");
        project.set("buildModel", buildModel);
        if (warningMessage != null) {
            project.set("warnings", warnings);
        }
        ObjectNode configuration = objectMapper.createObjectNode();
        if (seconds == null) {
            configuration.putNull("timeoutSeconds");
        } else {
            configuration.put("timeoutSeconds", seconds);
        }
        configuration.put("absolutePaths", false);
        ObjectNode healthData = objectMapper.createObjectNode();
        healthData.put("status", "Ready");
        healthData.put("message", "JavaLens MCP Server is operational");
        healthData.put("version", org.javalens.mcp.protocol.McpProtocolHandler.serverVersion());
        healthData.put("startedAt", java.time.Instant.parse(health.at("/data/startedAt").asText()).toString());
        String uptime = health.at("/data/uptime").asText();
        healthData.put("uptime", uptime.matches("\\d+ (seconds|minutes|hours)") ? uptime : "<not an uptime>");
        healthData.put("diskSync", "strict");
        healthData.set("project", project);
        healthData.putObject("java")
            .put("version", System.getProperty("java.version"))
            .put("vendor", System.getProperty("java.vendor"));
        healthData.putObject("os")
            .put("name", System.getProperty("os.name"))
            .put("arch", System.getProperty("os.arch"));
        healthData.putObject("capabilities")
            .put("findReferences", true).put("findImplementations", true).put("typeHierarchy", true)
            .put("refactoring", true).put("diagnostics", true);
        healthData.set("configuration", configuration);
        healthData.put("toolCount", 75);
        ObjectNode expectedHealth = objectMapper.createObjectNode().put("success", true);
        expectedHealth.set("data", healthData);

        ObjectNode timeout = objectMapper.createObjectNode().put("success", false);
        timeout.putObject("error")
            .put("code", "TIMEOUT")
            .put("message", "Operation 'get_complexity_metrics' timed out after " + seconds + " seconds")
            .put("hint", "Narrow the request, or raise JAVALENS_TIMEOUT_SECONDS (5 to 300 seconds; "
                + "unset means no limit) and restart JavaLens");

        assertAll(
            () -> assertEquals(expectedLoad, load, "load_project"),
            () -> assertEquals(expectedHealth, health, "health_check"),
            () -> assertEquals(List.of(), startedByLoadAndHealth, "load_project and health_check start no deadline"),
            () -> assertEquals(seconds == null ? 0 : 2, started.size(),
                "a tool call starts a deadline at each seam exactly when a limit is set"),
            () -> {
                if (seconds == null) {
                    assertEquals(true, limited.at("/success").asBoolean(), () -> "unlimited: " + limited);
                } else {
                    assertEquals(timeout, limited, "limited");
                }
            });
    }

    private static List<String> warningCodes(JsonNode warnings) {
        List<String> codes = new ArrayList<>();
        warnings.forEach(warning -> codes.add(warning.path("code").asText()));
        return codes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
