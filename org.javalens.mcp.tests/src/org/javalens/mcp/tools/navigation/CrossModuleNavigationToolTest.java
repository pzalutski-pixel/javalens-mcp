package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module navigation through the actual MCP tool boundary an AI agent uses.
 *
 * <p>The engine-level {@code MultiModuleMavenTest} verifies that {@code SearchService}
 * resolves cross-module references after the Bug C fix. This test verifies the same
 * resolution comes back correctly through {@code FindReferencesTool.execute(args)} —
 * the JSON-RPC entry point an MCP client invokes — so a regression in the wrapper
 * (response shape, path serialization, container resolution) would surface here even if
 * the engine remained correct.
 */
class CrossModuleNavigationToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("find_references through MCP tool resolves callers across reactor modules")
    void findReferencesAcrossModulesViaMcpTool() throws Exception {
        String mvn = resolveMavenBinary();
        if (Boolean.parseBoolean(System.getenv("JAVALENS_TESTS_REQUIRE_TOOLS"))) {
            assertNotNull(mvn,
                "JAVALENS_TESTS_REQUIRE_TOOLS=true requires Maven for cross-module navigation tests");
        } else {
            Assumptions.assumeTrue(mvn != null,
                "Maven binary unavailable; cross-module navigation needs the reactor loaded");
        }

        // Point ProjectImporter at the same Maven binary the test bootstraps with.
        String previous = System.getProperty("javalens.maven.binary");
        System.setProperty("javalens.maven.binary", mvn);
        try {
            Path projectRoot = helper.copyFixture("multi-module-maven");
            // Seed ~/.m2 with the sibling artifacts so dependency:build-classpath resolves.
            runMaven(mvn, projectRoot, "install", "-DskipTests", "-B", "-fae", "-q");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);

            FindReferencesTool tool = new FindReferencesTool(() -> service);
            ObjectMapper mapper = new ObjectMapper();

            // Greeter is declared in :api at line 2 (0-based) where the type name starts
            // at column 17 of "public interface Greeter {".
            Path greeter = projectRoot.resolve("api/src/main/java/com/example/api/Greeter.java");
            ObjectNode args = mapper.createObjectNode();
            args.put("filePath", greeter.toString());
            args.put("line", 2);
            args.put("column", 17);

            ToolResponse response = tool.execute(args);
            assertTrue(response.isSuccess(),
                "FindReferencesTool.execute should succeed; got error: " +
                (response.getError() != null ? response.getError().getCode() : "n/a"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> references = (List<Map<String, Object>>) data.get("references");
            assertNotNull(references, "Response must include a 'references' array");

            // The MCP boundary serializes file paths as strings — assert a cross-module
            // hit lands. GreeterImpl lives in :impl and references Greeter from :api.
            boolean foundCrossModule = references.stream().anyMatch(ref -> {
                Object fp = ref.get("filePath");
                return fp != null && fp.toString().contains("GreeterImpl");
            });
            assertTrue(foundCrossModule,
                "Expected GreeterImpl in :impl to be returned as a cross-module reference " +
                "to Greeter declared in :api. Got: " + references);
        } finally {
            if (previous == null) System.clearProperty("javalens.maven.binary");
            else System.setProperty("javalens.maven.binary", previous);
        }
    }

    private static void runMaven(String mvn, Path projectRoot, String... goals)
            throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(mvn);
        for (String g : goals) command.add(g);
        Process p = new ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start();
        StringBuilder captured = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (captured.length() < 8192) captured.append(line).append('\n');
            }
        }
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new RuntimeException("mvn " + String.join(" ", goals) + " timed out\n" + captured);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("mvn " + String.join(" ", goals)
                + " failed with exit code " + p.exitValue() + "\n" + captured);
        }
    }

    private static String resolveMavenBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String name = isWindows ? "mvn.cmd" : "mvn";
        try {
            Process p = new ProcessBuilder(name, "-v").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            p.waitFor();
            if (p.exitValue() == 0) return name;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }
        Path wrapperDists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperDists)) return null;
        try (Stream<Path> distros = Files.list(wrapperDists)) {
            for (Path distro : distros.toList()) {
                try (Stream<Path> hashes = Files.list(distro)) {
                    for (Path h : hashes.toList()) {
                        Path bin = h.resolve("bin").resolve(name);
                        if (Files.isRegularFile(bin)) return bin.toString();
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }
}
