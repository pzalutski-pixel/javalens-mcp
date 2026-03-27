package org.javalens.mcp;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.core.IJdtService;
import org.javalens.mcp.tools.*;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.javalens.core.JdtServiceImpl;

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
        JavaLensApplication app = instance;
        return app != null ? app.loadingState : ProjectLoadingState.NOT_LOADED;
    }

    public static String getLoadingError() {
        JavaLensApplication app = instance;
        return app != null ? app.loadingError : null;
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Use System.err for initial logs to ensure visibility
        System.err.println("=== JavaLens MCP Server Starting (v2.0.0-SNAPSHOT) ===");
        instance = this;

        // Force start critical bundles for headless environment
        ensureBundlesStarted();

        try {
            toolRegistry = new ToolRegistry();
            registerTools();
            protocolHandler = new McpProtocolHandler(toolRegistry);

            log.info("Registered {} tools", toolRegistry.getToolCount());

            // SYNC LOADING for One-Shot compatibility (Vibe)
            // This ensures that the server is READY before it starts responding to Vibe
            autoLoadProjectFromEnv();

            runMessageLoop();
        } catch (Throwable t) {
            log.error("Fatal error in application: {}", t.getMessage(), t);
            System.err.println("FATAL: " + t.getMessage());
        }

        System.err.println("=== JavaLens MCP Server Stopped ===");
        return IApplication.EXIT_OK;
    }

    private void ensureBundlesStarted() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(JavaLensApplication.class);
            if (bundle != null && bundle.getBundleContext() != null) {
                for (Bundle b : bundle.getBundleContext().getBundles()) {
                    String name = b.getSymbolicName();
                    if (name.equals("org.eclipse.equinox.preferences") || 
                        name.equals("org.eclipse.core.resources") ||
                        name.equals("org.eclipse.jdt.core")) {
                        if (b.getState() != Bundle.ACTIVE) {
                            try {
                                b.start(Bundle.START_TRANSIENT);
                            } catch (Exception e) {
                                System.err.println("Failed to start bundle " + name + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("Warning: Bundle starter failed: " + t.getMessage());
        }
    }

    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("JAVA_PROJECT_PATH not set");
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("Path does not exist: {}", projectPath);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = "Path does not exist: " + projectPath;
            return;
        }

        log.info("Loading project: {}", path);
        loadingState = ProjectLoadingState.LOADING;

        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            this.jdtService = service;
            loadingState = ProjectLoadingState.LOADED;
            log.info("Project loaded: {} files", service.getSourceFileCount());
        } catch (Exception e) {
            log.error("Loading failed: {}", e.getMessage(), e);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = e.getMessage();
        }
    }

    private void registerTools() {
        toolRegistry.register(new HealthCheckTool(() -> jdtService != null, () -> toolRegistry.getToolCount(), () -> loadingState, () -> loadingError));
        toolRegistry.register(new LoadProjectTool(service -> { this.jdtService = service; loadingState = ProjectLoadingState.LOADED; }));
        
        // Navigation & Analysis
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

    private void runMessageLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
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
                    log.error("Error processing message", e);
                }
            }
        } catch (Exception e) {
            log.error("Error in message loop", e);
        }
    }

    @Override
    public void stop() {
        running = false;
    }
}
