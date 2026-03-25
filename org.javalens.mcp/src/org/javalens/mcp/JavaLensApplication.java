package org.javalens.mcp;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OSGi application entry point for JavaLens MCP server.
 * Reads JSON-RPC messages from stdin and writes responses to stdout.
 */
public class JavaLensApplication implements IApplication {

    private static final Logger log = LoggerFactory.getLogger(JavaLensApplication.class);

    private volatile boolean running = true;
    private volatile IJdtService jdtService;
    private volatile ProjectLoadingState loadingState = ProjectLoadingState.NOT_LOADED;
    private volatile String loadingError = null;
    private ToolRegistry toolRegistry;
    private McpProtocolHandler protocolHandler;

    private static volatile JavaLensApplication instance;

    public static ProjectLoadingState getLoadingState() {
        return instance != null ? instance.loadingState : ProjectLoadingState.NOT_LOADED;
    }

    public static String getLoadingError() {
        return instance != null ? instance.loadingError : null;
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Use System.err for guaranteed logging visibility in javalens.log
        System.err.println("=== JavaLens MCP Server Starting (v2.0.0-SNAPSHOT) ===");
        instance = this;

        try {
            // Initialize tools
            toolRegistry = new ToolRegistry();
            registerTools();
            System.err.println("Registered " + toolRegistry.getToolCount() + " tools");

            // Initialize protocol
            protocolHandler = new McpProtocolHandler(toolRegistry);

            // START BACKGROUND LOADER
            // We use a dedicated thread to ensure the message loop is NEVER blocked
            Thread loaderThread = new Thread(() -> {
                try {
                    autoLoadProjectFromEnv();
                } catch (Throwable t) {
                    System.err.println("FATAL: ProjectLoader thread crashed");
                    t.printStackTrace();
                }
            }, "ProjectLoader");
            loaderThread.setDaemon(true);
            loaderThread.setPriority(Thread.MIN_PRIORITY); // Let message loop have CPU priority
            loaderThread.start();
            System.err.println("Background ProjectLoader thread started");

            // ENTER MESSAGE LOOP (Blocking)
            System.err.println("Entering MCP message loop...");
            runMessageLoop();

        } catch (Throwable t) {
            System.err.println("FATAL: Server failed to start");
            t.printStackTrace();
        }

        System.err.println("=== JavaLens MCP Server Stopped ===");
        return IApplication.EXIT_OK;
    }

    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            System.err.println("JAVA_PROJECT_PATH not set. Waiting for manual load_project call.");
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            System.err.println("ERROR: Path does not exist: " + projectPath);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = "Path does not exist: " + projectPath;
            return;
        }

        System.err.println(">>> STARTING PROJECT SCAN: " + path);
        loadingState = ProjectLoadingState.LOADING;

        long startTime = System.currentTimeMillis();
        try {
            // This is the heavy part that must not block the main thread
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            this.jdtService = service;
            loadingState = ProjectLoadingState.LOADED;
            
            long totalTime = System.currentTimeMillis() - startTime;
            System.err.println(">>> PROJECT LOADED SUCCESSFULLY in " + totalTime + " ms");
            System.err.println(">>> Files indexed: " + service.getSourceFileCount());
        } catch (Throwable e) {
            System.err.println("CRITICAL: Failed to load project");
            e.printStackTrace();
            loadingState = ProjectLoadingState.FAILED;
            loadingError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void runMessageLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {

            while (running) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.isBlank()) continue;

                try {
                    String response = protocolHandler.processMessage(line);
                    if (response != null) {
                        writer.println(response);
                        writer.flush();
                    }
                } catch (Exception e) {
                    System.err.println("Error processing message: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("FATAL: Message loop exception");
            e.printStackTrace();
        }
    }

    private void registerTools() {
        toolRegistry.register(new HealthCheckTool(() -> jdtService != null, () -> toolRegistry.getToolCount(), () -> loadingState, () -> loadingError));
        toolRegistry.register(new LoadProjectTool(service -> this.jdtService = service));
        toolRegistry.register(new SearchSymbolsTool(() -> jdtService));
        toolRegistry.register(new GoToDefinitionTool(() -> jdtService));
        toolRegistry.register(new FindReferencesTool(() -> jdtService));
        toolRegistry.register(new FindImplementationsTool(() -> jdtService));
        toolRegistry.register(new GetTypeHierarchyTool(() -> jdtService));
        toolRegistry.register(new GetDocumentSymbolsTool(() -> jdtService));
        toolRegistry.register(new GetTypeMembersTool(() -> jdtService));
        toolRegistry.register(new GetClasspathInfoTool(() -> jdtService));
        toolRegistry.register(new GetProjectStructureTool(() -> jdtService));
        toolRegistry.register(new GetSymbolInfoTool(() -> jdtService));
        toolRegistry.register(new GetTypeAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetMethodAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetFieldAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetHoverInfoTool(() -> jdtService));
        toolRegistry.register(new GetJavadocTool(() -> jdtService));
        toolRegistry.register(new GetSignatureHelpTool(() -> jdtService));
        toolRegistry.register(new GetEnclosingElementTool(() -> jdtService));
        toolRegistry.register(new GetSuperMethodTool(() -> jdtService));
        toolRegistry.register(new GetDiagnosticsTool(() -> jdtService));
        toolRegistry.register(new ValidateSyntaxTool(() -> jdtService));
        toolRegistry.register(new GetCallHierarchyIncomingTool(() -> jdtService));
        toolRegistry.register(new GetCallHierarchyOutgoingTool(() -> jdtService));
        toolRegistry.register(new FindFieldWritesTool(() -> jdtService));
        toolRegistry.register(new FindTestsTool(() -> jdtService));
        toolRegistry.register(new FindUnusedCodeTool(() -> jdtService));
        toolRegistry.register(new FindPossibleBugsTool(() -> jdtService));
        toolRegistry.register(new RenameSymbolTool(() -> jdtService));
        toolRegistry.register(new OrganizeImportsTool(() -> jdtService));
        toolRegistry.register(new ExtractVariableTool(() -> jdtService));
        toolRegistry.register(new ExtractMethodTool(() -> jdtService));
        toolRegistry.register(new FindAnnotationUsagesTool(() -> jdtService));
        toolRegistry.register(new FindTypeInstantiationsTool(() -> jdtService));
        toolRegistry.register(new FindCastsTool(() -> jdtService));
        toolRegistry.register(new FindInstanceofChecksTool(() -> jdtService));
        toolRegistry.register(new FindThrowsDeclarationsTool(() -> jdtService));
        toolRegistry.register(new FindCatchBlocksTool(() -> jdtService));
        toolRegistry.register(new FindMethodReferencesTool(() -> jdtService));
        toolRegistry.register(new FindTypeArgumentsTool(() -> jdtService));
        toolRegistry.register(new AnalyzeFileTool(() -> jdtService));
        toolRegistry.register(new AnalyzeTypeTool(() -> jdtService));
        toolRegistry.register(new AnalyzeMethodTool(() -> jdtService));
        toolRegistry.register(new GetTypeUsageSummaryTool(() -> jdtService));
        toolRegistry.register(new ExtractConstantTool(() -> jdtService));
        toolRegistry.register(new InlineVariableTool(() -> jdtService));
        toolRegistry.register(new InlineMethodTool(() -> jdtService));
        toolRegistry.register(new ChangeMethodSignatureTool(() -> jdtService));
        toolRegistry.register(new ExtractInterfaceTool(() -> jdtService));
        toolRegistry.register(new ConvertAnonymousToLambdaTool(() -> jdtService));
        toolRegistry.register(new SuggestImportsTool(() -> jdtService));
        toolRegistry.register(new GetQuickFixesTool(() -> jdtService));
        toolRegistry.register(new ApplyQuickFixTool(() -> jdtService));
        toolRegistry.register(new GetComplexityMetricsTool(() -> jdtService));
        toolRegistry.register(new GetDependencyGraphTool(() -> jdtService));
        toolRegistry.register(new FindCircularDependenciesTool(() -> jdtService));
    }

    @Override
    public void stop() {
        running = false;
    }
}
