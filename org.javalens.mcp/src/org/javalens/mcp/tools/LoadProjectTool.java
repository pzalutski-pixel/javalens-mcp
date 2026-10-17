package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Load a Java project for analysis.
 * MUST be called before using navigation/analysis tools.
 *
 * Adapted from src/main/java/dev/javalens/tools/LoadProjectTool.java
 */
public class LoadProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadProjectTool.class);

    /** Numbers each load before it starts and installs the service it loads. */
    public interface LoadRequests {
        /** Registers a load that is about to start and returns its request number. */
        long request();

        /** Installs the service loaded for {@code request}. */
        void install(long request, IJdtService service);

        /** Reports that the load of {@code request} failed with {@code error}. */
        void fail(long request, String error);
    }

    private final LoadRequests loadRequests;
    private final Supplier<JdtServiceImpl> services;

    public LoadProjectTool(LoadRequests loadRequests) {
        this(loadRequests, JdtServiceImpl::new);
    }

    /** Loads each project through a service from {@code services}. */
    public LoadProjectTool(LoadRequests loadRequests, Supplier<JdtServiceImpl> services) {
        this.loadRequests = loadRequests;
        this.services = services;
    }

    /** Installs every loaded service through {@code serviceSetter}. */
    public LoadProjectTool(Consumer<IJdtService> serviceSetter) {
        this(new LoadRequests() {
            @Override
            public long request() {
                return 0;
            }

            @Override
            public void install(long request, IJdtService service) {
                serviceSetter.accept(service);
            }

            @Override
            public void fail(long request, String error) {
                // The failure reaches the caller through the tool's error response.
            }
        });
    }

    @Override
    public String getName() {
        return "load_project";
    }

    @Override
    public String getDescription() {
        return """
            Load a Java project for analysis.
            MUST be called before using other analysis tools.

            USAGE: load_project(projectPath="/path/to/project")
            OUTPUT: Project structure summary including packages, source files, buildModel (build system, source of the structure: build-tool, static-read or convention, analysis level, modules) and warnings (present only when the load was degraded)

            Supports:
            - Maven projects (pom.xml)
            - Gradle projects (settings.gradle, settings.gradle.kts, build.gradle or build.gradle.kts)
            - Bazel projects (MODULE.bazel or WORKSPACE)
            - Plain Java projects with src/ directory

            WORKFLOW:
            1. Call load_project with absolute path to project root
            2. Wait for project to load (may take a few seconds for large projects)
            3. Use health_check to verify project is loaded
            4. Begin using analysis tools (search_symbols, find_references, etc.)
            """ + syncContract();
    }

    /** Mode-matched sync contract: the description's claim must equal the behavior. */
    private static String syncContract() {
        if (org.javalens.core.sync.DiskSyncMode.fromEnvironment(System.getenv("JAVALENS_DISK_SYNC"))
                == org.javalens.core.sync.DiskSyncMode.MANUAL) {
            return """

                SYNC (manual mode): answers reflect the last load. After writing, adding,
                or deleting source files, call load_project again to refresh the model.
                """;
        }
        return """

            SYNC (strict mode): answers are always verified against the files on disk -
            no reload is needed after editing, adding, or deleting source files. Call
            load_project only on first use, when a response reports RELOAD_REQUIRED
            (a build file changed), or to rebuild everything from scratch.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("projectPath", "string", "Absolute path to the project root directory containing pom.xml or build.gradle")
            .build();
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("projectPath")) {
            return ToolResponse.invalidParameter("projectPath", "Required parameter missing");
        }

        String projectPath = arguments.get("projectPath").asText();

        try {
            Path path = Path.of(projectPath).toAbsolutePath().normalize();

            // Validate path
            if (!Files.exists(path)) {
                return ToolResponse.error("FILE_NOT_FOUND",
                    "Project path not found: " + projectPath,
                    "Verify the path exists and is accessible");
            }

            if (!Files.isDirectory(path)) {
                return ToolResponse.error("INVALID_PARAMETER",
                    "Project path is not a directory: " + projectPath,
                    "Provide path to project root directory");
            }

            log.info("Loading project: {}", path);

            // Register the load before it starts, so it supersedes any earlier request
            long request = loadRequests.request();
            JdtServiceImpl service = services.get();
            try {
                service.loadProject(path);
            } catch (Exception e) {
                loadRequests.fail(request, e.getMessage());
                throw e;
            }

            // Store service for other tools to use
            loadRequests.install(request, service);

            // Build response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loaded", true);
            // The absolute normalized root the load used, rendered as get_project_structure
            // and get_classpath_info render it in their projectRoot.
            result.put("projectPath", service.getProjectRoot().toString());
            result.put("sourceFileCount", service.getSourceFileCount());
            result.put("packageCount", service.getPackageCount());

            // Include first 20 packages
            List<String> packages = service.getPackages();
            result.put("packages", packages.size() <= 20 ? packages : packages.subList(0, 20));

            result.put("classpathEntryCount", service.getClasspathEntryCount());
            result.put("loadedAt", service.getLoadedAt().toString());

            // Where the structure came from, and every warning the load produced (e.g., mvn
            // subprocess failure), exactly as health_check reports them.
            LoadReport.putInto(result, service);
            if (!service.getWarnings().isEmpty()) {
                log.warn("Project loaded with {} warning(s)", service.getWarnings().size());
            }

            log.info("Project loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to load project", e);
            return ToolResponse.internalError(e);
        }
    }
}
