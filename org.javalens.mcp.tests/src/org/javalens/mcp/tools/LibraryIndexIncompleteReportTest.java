package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * load_project and health_check report LIBRARY_INDEX_INCOMPLETE, naming the library, for a
 * library JDT cannot index (a file that is not a zip archive), exactly as the load's warnings,
 * through execute() and through the JSON-RPC envelope alike.
 */
class LibraryIndexIncompleteReportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a library that is not a zip archive is reported LIBRARY_INDEX_INCOMPLETE by load_project and health_check")
    void unreadableLibrary_reportedByLoadAndHealth() throws IOException {
        Path broken = helper.getTempDirectory().resolve("libs/broken.jar");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "not a zip archive");
        EnvelopeHarness envelope = new EnvelopeHarness(null, () -> new JdtServiceImpl(withLibrary(broken)));
        ObjectNode loadArgs = envelope.args().put("projectPath", helper.getFixturePath("index-fidelity").toString());

        JsonNode load = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode health = envelope.assertEnvelopeFidelity("health_check", envelope.args());

        Map<String, Object> warning = new LinkedHashMap<>();
        warning.put("code", "LIBRARY_INDEX_INCOMPLETE");
        warning.put("message", "The search index of 1 library could not be built: "
            + broken.toAbsolutePath().normalize().toString().replace('\\', '/')
            + "; search-based results do not include its types");
        warning.put("remediation", "Make sure each library is a readable archive or class folder, "
            + "then reload the project; if the warning persists, restart JavaLens.");
        JsonNode expected = mapper.valueToTree(List.of(warning));
        assertAll(
            () -> assertEquals(true, load.path("data").path("loaded").asBoolean(), "load_project loaded"),
            () -> assertEquals(expected, load.path("data").get("warnings"), "load_project warnings"),
            () -> assertEquals(expected, health.path("data").path("project").get("warnings"), "health_check warnings"));
    }

    /** An importer whose resolved build model carries {@code library} after its own classpath. */
    private static ProjectImporter withLibrary(Path library) {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                BuildModel model = super.resolveModel(projectPath);
                List<String> classpath = new ArrayList<>(model.classpath());
                classpath.add(library.toAbsolutePath().normalize().toString());
                return new BuildModel(model.buildSystem(), model.provenance(), model.modules(), classpath,
                    model.processorJars(), model.analysisLevel(), model.inputs(), model.warnings());
            }
        };
    }
}
