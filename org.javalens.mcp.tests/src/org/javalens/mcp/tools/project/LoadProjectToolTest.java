package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.LoadProjectTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoadProjectToolTest {

    /**
     * simple-maven's packages, sorted: the directories under src/main/java and src/test/java
     * that hold Java files (com.example is in both).
     */
    private static final List<String> SIMPLE_MAVEN_PACKAGES = List.of(
        "com.example",
        "com.example.cycledemo.a",
        "com.example.cycledemo.b",
        "com.example.genericunused",
        "com.example.service",
        "com.example.staticcycle.x",
        "com.example.staticcycle.y");

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

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private LoadProjectTool tool;
    private ObjectMapper objectMapper;
    private AtomicReference<IJdtService> serviceRef;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        serviceRef = new AtomicReference<>();
        tool = new LoadProjectTool(serviceRef::set);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    /** {@code buildModel.buildSystem} of a load_project response, or null when it has no buildModel. */
    private static Object buildSystemOf(Map<String, Object> data) {
        return data.get("buildModel") instanceof Map<?, ?> buildModel ? buildModel.get("buildSystem") : null;
    }

    @Test @DisplayName("simple-maven loads with the exact response at both seams; projectPath is the absolute normalized root the load used")
    void loadsProjectComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        assertLoadPayload(args, simpleMavenData());
        // execute() installed the service it loaded, for the root it was asked to load.
        assertEquals(projectPath.toAbsolutePath().normalize(), serviceRef.get().getProjectRoot(),
            "root of the installed service");
    }

    @Test @DisplayName("missing projectPath -> exact INVALID_PARAMETER")
    void requiresProjectPath() {
        ToolResponse empty = tool.execute(objectMapper.createObjectNode());
        assertFalse(empty.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, empty.getError().getCode());
        assertEquals("Invalid parameter 'projectPath': Required parameter missing",
            empty.getError().getMessage());

        ToolResponse nullArgs = tool.execute(null);
        assertFalse(nullArgs.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, nullArgs.getError().getCode());
    }

    @Test @DisplayName("non-existent path -> FILE_NOT_FOUND; file (not dir) -> INVALID_PARAMETER")
    void handlesInvalidPaths() {
        ObjectNode nonExistent = objectMapper.createObjectNode();
        nonExistent.put("projectPath", "/nonexistent/path/project");
        ToolResponse r1 = tool.execute(nonExistent);
        assertFalse(r1.isSuccess());
        assertEquals("FILE_NOT_FOUND", r1.getError().getCode());
        assertEquals("Project path not found: /nonexistent/path/project", r1.getError().getMessage());

        // File instead of directory (pom.xml). The message carries a machine-specific path.
        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("projectPath", projectPath.resolve("pom.xml").toString());
        ToolResponse r2 = tool.execute(filePath);
        assertFalse(r2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r2.getError().getCode());
        assertTrue(r2.getError().getMessage().startsWith("Project path is not a directory: ")
                && r2.getError().getMessage().endsWith("pom.xml"),
            "got: " + r2.getError().getMessage());
    }

    // ========== Bug-fix surface tests at the MCP boundary ===========================
    // These verify what an AI agent actually sees in the load_project response — not
    // just that the engine state is correct, but that the JSON-serialized tool response
    // carries the bug-fix-specific fields (warnings, buildModel, classpathEntryCount,
    // packages) faithfully.

    @Test @DisplayName("a build tool that runs and fails surfaces exactly one BUILD_TOOL_FAILED entry in the response's 'warnings' array")
    void subprocessFailureSurfacesWarningsArrayInResponse() throws Exception {
        // broken-maven-deps declares a non-existent dependency, so Maven runs and exits
        // non-zero. Like the clean-load tests here, this needs Maven on PATH. The response
        // must expose the failure as a structured field for the agent to see.
        Path brokenPath = helper.getFixturePath("broken-maven-deps");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", brokenPath.toString());

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "load_project itself succeeds even when the build tool fails");
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings =
            (List<Map<String, Object>>) data.getOrDefault("warnings", List.of());
        assertEquals(List.of(LoadWarning.BUILD_TOOL_FAILED),
            warnings.stream().map(w -> w.get("code")).toList(), "warning codes");

        // The warning carries code, message, remediation and the module whose dependencies
        // Maven could not resolve, so the agent can act on it.
        Map<String, Object> warning = warnings.get(0);
        assertAll(
            () -> assertEquals(List.of("code", "message", "remediation", "module"), List.copyOf(warning.keySet()),
                "warning fields in order"),
            () -> assertEquals("com.example:broken-maven-deps", warning.get("module"), "warning module"),
            () -> assertEquals("If the module depends on another module of this build, install the build once "
                    + "('mvn install -DskipTests') so Maven can resolve it, then reload the project.",
                warning.get("remediation"), "warning remediation"),
            // Maven exits with 1 when the build fails. The message ends with the last lines
            // of Maven's own error output, whose wording belongs to Maven, so only the part
            // MavenImporter composes is pinned.
            () -> assertTrue(String.valueOf(warning.get("message"))
                    .startsWith("Maven exited with code 1 and did not resolve the dependencies of module "
                        + "'com.example:broken-maven-deps', so they are not on the classpath. Last output: "),
                () -> "warning message: " + warning.get("message")));
    }

    @Test @DisplayName("clean project response: no 'warnings' field when nothing went wrong")
    void cleanProjectOmitsWarningsField() {
        // simple-maven declares its level and has no dependencies, so Maven's classpath step
        // succeeds and the load meets nothing worth a warning.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertEquals(false, data.containsKey("warnings"),
            "'warnings' is omitted when the load is clean; got: " + data.get("warnings"));
    }

    @Test @DisplayName("simple-maven response carries exactly the documented fields; buildModel reports Maven's model, level 21, the root as its one module")
    void responseExposesDocumentedFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // The build system is reported only inside buildModel, and a clean load carries no
        // warnings. Maven reports the model (build-tool); the pom declares
        // maven.compiler.source 21; the root is the only module.
        assertAll(
            () -> assertEquals(List.of("loaded", "projectPath", "sourceFileCount", "packageCount",
                    "packages", "classpathEntryCount", "loadedAt", "buildModel"),
                List.copyOf(data.keySet()), "response fields in order"),
            () -> assertEquals(SIMPLE_MAVEN_BUILD_MODEL, data.get("buildModel"), "buildModel"),
            () -> assertEquals(List.of("buildSystem", "source", "analysisLevel", "moduleCount", "modules"),
                data.get("buildModel") instanceof Map<?, ?> buildModel ? List.copyOf(buildModel.keySet()) : null,
                "buildModel fields in order"));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("multi-module-maven reports the root and its api, impl and web modules, each module contributing one package")
    @SuppressWarnings("unchecked")
    void multiModuleMaven_loadsThreeModulesWithExpectedPackages() {
        Path multiModule = helper.getFixturePath("multi-module-maven");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", multiModule.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "multi-module-maven must load successfully; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("loaded"));
        // The root pom declares api, impl and web and maven.compiler.source 21; the root
        // itself is the first module. A module's id is its groupId:artifactId and its dir
        // its directory relative to the root, "." for the root.
        assertEquals(Map.of(
                "buildSystem", "maven",
                "source", "build-tool",
                "analysisLevel", "21",
                "moduleCount", 4,
                "modules", List.of(
                    Map.of("id", "com.example:multi-module-parent", "dir", "."),
                    Map.of("id", "com.example:api", "dir", "api"),
                    Map.of("id", "com.example:impl", "dir", "impl"),
                    Map.of("id", "com.example:web", "dir", "web"))),
            data.get("buildModel"), "buildModel");
        assertEquals(false, data.containsKey("buildSystem"),
            "the build system is reported only inside buildModel");

        // Three modules each declare one package: com.example.api, com.example.impl,
        // com.example.web. The packages list must include all three.
        List<String> packages = (List<String>) data.get("packages");
        java.util.Set<String> pkgSet = new java.util.HashSet<>(packages);
        assertTrue(pkgSet.contains("com.example.api"),
            "api module's package must appear; got: " + packages);
        assertTrue(pkgSet.contains("com.example.impl"),
            "impl module's package must appear; got: " + packages);
        assertTrue(pkgSet.contains("com.example.web"),
            "web module's package must appear; got: " + packages);

        // Three modules x one Java file each = exactly 3 source files, 3 packages.
        assertEquals(3, ((Number) data.get("sourceFileCount")).intValue(),
            "Greeter, GreeterImpl, GreeterController; got: " + data.get("sourceFileCount"));
        assertEquals(3, ((Number) data.get("packageCount")).intValue());
    }

    @Test
    @DisplayName("plain-java project (no pom.xml, no build.gradle) loads via src/ detection")
    void plainJavaProject_loadsWithoutBuildFile() {
        Path plain = helper.getFixturePath("plain-java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", plain.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "plain-java project must load — the tool's description claims support for plain " +
                "Java projects with src/ directory. Got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("loaded"));
        // No build file: the root is the one module, laid out by convention, and with no
        // declared level the project is analyzed at the running JVM's level.
        assertEquals(Map.of(
                "buildSystem", "unknown",
                "source", "convention",
                "analysisLevel", String.valueOf(Runtime.version().feature()),
                "moduleCount", 1,
                "modules", List.of(Map.of("id", ".", "dir", "."))),
            data.get("buildModel"), "buildModel");
        assertEquals(false, data.containsKey("buildSystem"),
            "the build system is reported only inside buildModel");

        assertEquals(2, ((Number) data.get("sourceFileCount")).intValue(),
            "plain-java fixture has exactly two source files");
    }

    @Test
    @DisplayName("a relative projectPath, taken against the working directory, loads simple-maven and echoes its absolute normalized root at both seams")
    void relativePath_normalizedToAbsolute() {
        // The same simple-maven directory, named relative to the working directory, through
        // whatever '..' segments that takes.
        Path relative = Path.of("").toAbsolutePath().relativize(projectPath.toAbsolutePath().normalize());
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", relative.toString());

        assertLoadPayload(args, simpleMavenData());
    }

    @Test
    @DisplayName("loading a second project replaces the service registered by the first, and each response echoes its own root")
    void reload_replacesServiceWithNewProject() {
        // Load simple-maven first.
        ObjectNode firstArgs = objectMapper.createObjectNode();
        firstArgs.put("projectPath", projectPath.toString());
        ToolResponse r1 = tool.execute(firstArgs);
        assertTrue(r1.isSuccess());
        IJdtService firstService = serviceRef.get();
        assertNotNull(firstService);

        // Now load multi-module-maven via the same tool instance.
        Path multiModule = helper.getFixturePath("multi-module-maven");
        ObjectNode secondArgs = objectMapper.createObjectNode();
        secondArgs.put("projectPath", multiModule.toString());
        ToolResponse r2 = tool.execute(secondArgs);
        assertTrue(r2.isSuccess(),
            "Second load_project call must succeed; got error: " +
                (r2.getError() != null ? r2.getError().getMessage() : "n/a"));

        IJdtService secondService = serviceRef.get();
        assertNotNull(secondService);
        assertNotSame(firstService, secondService,
            "Each load_project invocation must produce a fresh JdtService — otherwise " +
                "subsequent tool calls would route through the previously-loaded project's index.");
        assertAll(
            () -> assertEquals(projectPath.toAbsolutePath().normalize().toString(), getData(r1).get("projectPath"),
                "first response's projectPath"),
            () -> assertEquals(multiModule.toAbsolutePath().normalize().toString(), getData(r2).get("projectPath"),
                "second response's projectPath"));
    }

    @Test
    @DisplayName("simple-gradle loads with buildModel.buildSystem='gradle' and Hello.java visible")
    @SuppressWarnings("unchecked")
    void simpleGradle_loadsAsGradleProject() {
        Path gradleProject = helper.getFixturePath("simple-gradle");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", gradleProject.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "simple-gradle must load — the tool's description claims support for " +
                "Gradle projects (build.gradle). Got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("loaded"));
        assertEquals("gradle", buildSystemOf(data),
            "buildModel.buildSystem must be lowercase 'gradle' for a build.gradle project; got: "
                + data.get("buildModel"));
        assertEquals(false, data.containsKey("buildSystem"),
            "the build system is reported only inside buildModel");

        List<String> packages = (List<String>) data.get("packages");
        assertTrue(packages.contains("com.example"),
            "Hello.java in com.example must contribute its package; got: " + packages);
        assertEquals(1, ((Number) data.get("sourceFileCount")).intValue(),
            "simple-gradle has exactly one source file (Hello.java)");
    }

    @Test
    @DisplayName("many-packages fixture (21 packages) is capped at 20 in the response")
    @SuppressWarnings("unchecked")
    void manyPackages_listIsCappedAtTwenty() {
        Path many = helper.getFixturePath("many-packages");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", many.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Project has exactly 21 packages (pkg00..pkg20). packageCount must reflect
        // the true total, but the `packages` list in the response is capped at 20.
        assertEquals(21, ((Number) data.get("packageCount")).intValue(),
            "Fixture declares 21 packages; packageCount must report the true total " +
                "regardless of the truncation cap. Got: " + data.get("packageCount"));

        List<String> packages = (List<String>) data.get("packages");
        assertEquals(20, packages.size(),
            "packages list must be capped at 20 entries even when packageCount > 20; got: "
                + packages.size() + " entries: " + packages);
    }

    // ========== Helpers ==========

    /**
     * load_project's data for simple-maven, without loadedAt: 71 source files (58 in
     * com.example under src/main/java, 3 in com.example under src/test/java, 5 in
     * genericunused and one in each of the other five packages) in 7 packages; a raw
     * classpath of the JRE container and the two source folders (the pom declares no
     * dependencies and target/ holds no compiled output); a clean load, so no warnings.
     */
    private Map<String, Object> simpleMavenData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", true);
        data.put("projectPath", projectPath.toAbsolutePath().normalize().toString());
        data.put("sourceFileCount", 71);
        data.put("packageCount", 7);
        data.put("packages", SIMPLE_MAVEN_PACKAGES);
        data.put("classpathEntryCount", 3);
        data.put("buildModel", SIMPLE_MAVEN_BUILD_MODEL);
        return data;
    }

    /**
     * The load_project payload for {@code args} through execute() and through the JSON-RPC
     * envelope both equal a success carrying {@code expectedData}.
     */
    private void assertLoadPayload(ObjectNode args, Map<String, Object> expectedData) {
        JsonNode expected = objectMapper.valueToTree(Map.of("success", true, "data", expectedData));
        Instant before = Instant.now();
        JsonNode direct = objectMapper.valueToTree(tool.execute(args.deepCopy()));
        JsonNode wire = new EnvelopeHarness(null).assertEnvelopeFidelity("load_project", args);
        Instant after = Instant.now();
        assertAll(
            () -> assertEquals(expected, comparable(direct, before, after), "execute() payload for " + args),
            () -> assertEquals(expected, comparable(wire, before, after), "envelope payload for " + args));
    }

    /**
     * {@code payload} with loadedAt removed and packages sorted. loadedAt is when the load
     * finished, so it can only be checked to fall within the call. packages come in the
     * order the file system lists the source directories, which differs between file
     * systems, so they are compared as a sorted list.
     */
    private static JsonNode comparable(JsonNode payload, Instant before, Instant after) {
        ObjectNode copy = payload.deepCopy();
        if (!(copy.get("data") instanceof ObjectNode data)) {
            return copy; // a failed load: compared whole, so the assertion shows its error
        }
        Instant loadedAt = Instant.parse(data.path("loadedAt").asText());
        assertTrue(!loadedAt.isBefore(before) && !loadedAt.isAfter(after),
            () -> "loadedAt " + loadedAt + " must fall within the call, " + before + " to " + after);
        data.remove("loadedAt");
        List<String> packages = new ArrayList<>();
        data.path("packages").forEach(name -> packages.add(name.asText()));
        packages.sort(null);
        ArrayNode sorted = data.putArray("packages");
        packages.forEach(sorted::add);
        return copy;
    }
}
