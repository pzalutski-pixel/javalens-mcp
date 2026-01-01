package org.javalens.mcp;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.core.IJdtService;
import org.javalens.mcp.tools.HealthCheckTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.javalens.mcp.tools.GetClasspathInfoTool;
import org.javalens.mcp.tools.GetProjectStructureTool;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.javalens.mcp.tools.GetMethodAtPositionTool;
import org.javalens.mcp.tools.GetFieldAtPositionTool;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.javalens.mcp.tools.GetJavadocTool;
import org.javalens.mcp.tools.GetSignatureHelpTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.ValidateSyntaxTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindTestsTool;
import org.javalens.mcp.tools.FindUnusedCodeTool;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.FindCastsTool;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.javalens.mcp.tools.InlineVariableTool;
import org.javalens.mcp.tools.InlineMethodTool;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.javalens.mcp.tools.ToolRegistry;
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
 *
 * <p>Session isolation is handled by the JavaLensLauncher wrapper which
 * injects a unique UUID into the workspace path before OSGi starts.
 */
public class JavaLensApplication implements IApplication {

    private static final Logger log = LoggerFactory.getLogger(JavaLensApplication.class);

    private volatile boolean running = true;
    private volatile IJdtService jdtService;
    private ToolRegistry toolRegistry;
    private McpProtocolHandler protocolHandler;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        log.info("JavaLens MCP Server starting...");

        // Initialize tool registry and register tools
        toolRegistry = new ToolRegistry();
        registerTools();

        // Initialize protocol handler
        protocolHandler = new McpProtocolHandler(toolRegistry);

        log.info("Registered {} tools", toolRegistry.getToolCount());

        // Auto-load project from environment variable if set
        autoLoadProjectFromEnv();

        // Run the main message loop
        runMessageLoop();

        log.info("JavaLens MCP Server stopped");
        return IApplication.EXIT_OK;
    }

    /**
     * Auto-load project from JAVA_PROJECT_PATH environment variable.
     * This allows pre-configuring the project without calling load_project.
     */
    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("JAVA_PROJECT_PATH not set, waiting for load_project call");
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("JAVA_PROJECT_PATH points to non-existent path: {}", projectPath);
            return;
        }

        if (!Files.isDirectory(path)) {
            log.warn("JAVA_PROJECT_PATH is not a directory: {}", projectPath);
            return;
        }

        log.info("Auto-loading project from JAVA_PROJECT_PATH: {}", path);

        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            this.jdtService = service;
            log.info("Project auto-loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());
        } catch (Exception e) {
            log.error("Failed to auto-load project from JAVA_PROJECT_PATH: {}", e.getMessage(), e);
        }
    }

    private void registerTools() {
        // Register HealthCheckTool with suppliers for project status and tool count
        toolRegistry.register(new HealthCheckTool(
            () -> jdtService != null,
            () -> toolRegistry.getToolCount()
        ));

        // Register LoadProjectTool - stores the JdtService when a project is loaded
        toolRegistry.register(new LoadProjectTool(
            service -> this.jdtService = service
        ));

        // Batch 1: Core Navigation Tools
        toolRegistry.register(new SearchSymbolsTool(() -> jdtService));
        toolRegistry.register(new GoToDefinitionTool(() -> jdtService));
        toolRegistry.register(new FindReferencesTool(() -> jdtService));
        toolRegistry.register(new FindImplementationsTool(() -> jdtService));

        // Batch 2: Type Hierarchy & Document Symbols
        toolRegistry.register(new GetTypeHierarchyTool(() -> jdtService));
        toolRegistry.register(new GetDocumentSymbolsTool(() -> jdtService));
        toolRegistry.register(new GetTypeMembersTool(() -> jdtService));
        toolRegistry.register(new GetClasspathInfoTool(() -> jdtService));

        // Batch 3: Project Structure & Position Info
        toolRegistry.register(new GetProjectStructureTool(() -> jdtService));
        toolRegistry.register(new GetSymbolInfoTool(() -> jdtService));
        toolRegistry.register(new GetTypeAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetMethodAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetFieldAtPositionTool(() -> jdtService));
        toolRegistry.register(new GetHoverInfoTool(() -> jdtService));

        // Batch 4: Javadoc & Method Analysis
        toolRegistry.register(new GetJavadocTool(() -> jdtService));
        toolRegistry.register(new GetSignatureHelpTool(() -> jdtService));
        toolRegistry.register(new GetEnclosingElementTool(() -> jdtService));
        toolRegistry.register(new GetSuperMethodTool(() -> jdtService));

        // Batch 5: Diagnostics & Call Hierarchy
        toolRegistry.register(new GetDiagnosticsTool(() -> jdtService));
        toolRegistry.register(new ValidateSyntaxTool(() -> jdtService));
        toolRegistry.register(new GetCallHierarchyIncomingTool(() -> jdtService));
        toolRegistry.register(new GetCallHierarchyOutgoingTool(() -> jdtService));

        // Analysis tools
        toolRegistry.register(new FindFieldWritesTool(() -> jdtService));
        toolRegistry.register(new FindTestsTool(() -> jdtService));
        toolRegistry.register(new FindUnusedCodeTool(() -> jdtService));
        toolRegistry.register(new FindPossibleBugsTool(() -> jdtService));

        // Refactoring tools
        toolRegistry.register(new RenameSymbolTool(() -> jdtService));
        toolRegistry.register(new OrganizeImportsTool(() -> jdtService));
        toolRegistry.register(new ExtractVariableTool(() -> jdtService));
        toolRegistry.register(new ExtractMethodTool(() -> jdtService));

        // Fine-grained reference search (JDT-unique capabilities)
        toolRegistry.register(new FindAnnotationUsagesTool(() -> jdtService));
        toolRegistry.register(new FindTypeInstantiationsTool(() -> jdtService));
        toolRegistry.register(new FindCastsTool(() -> jdtService));
        toolRegistry.register(new FindInstanceofChecksTool(() -> jdtService));
        toolRegistry.register(new FindThrowsDeclarationsTool(() -> jdtService));
        toolRegistry.register(new FindCatchBlocksTool(() -> jdtService));
        toolRegistry.register(new FindMethodReferencesTool(() -> jdtService));
        toolRegistry.register(new FindTypeArgumentsTool(() -> jdtService));

        // Compound analysis tools
        toolRegistry.register(new AnalyzeFileTool(() -> jdtService));
        toolRegistry.register(new AnalyzeTypeTool(() -> jdtService));
        toolRegistry.register(new AnalyzeMethodTool(() -> jdtService));
        toolRegistry.register(new GetTypeUsageSummaryTool(() -> jdtService));

        // Advanced refactoring tools
        toolRegistry.register(new ExtractConstantTool(() -> jdtService));
        toolRegistry.register(new InlineVariableTool(() -> jdtService));
        toolRegistry.register(new InlineMethodTool(() -> jdtService));
        toolRegistry.register(new ChangeMethodSignatureTool(() -> jdtService));
        toolRegistry.register(new ExtractInterfaceTool(() -> jdtService));
        toolRegistry.register(new ConvertAnonymousToLambdaTool(() -> jdtService));

        // Quick fix tools
        toolRegistry.register(new SuggestImportsTool(() -> jdtService));
        toolRegistry.register(new GetQuickFixesTool(() -> jdtService));
        toolRegistry.register(new ApplyQuickFixTool(() -> jdtService));

        // Metrics tools
        toolRegistry.register(new GetComplexityMetricsTool(() -> jdtService));
        toolRegistry.register(new GetDependencyGraphTool(() -> jdtService));
        toolRegistry.register(new FindCircularDependenciesTool(() -> jdtService));
    }

    private void runMessageLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {

            log.debug("Entering message loop");

            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    log.debug("End of input stream, exiting");
                    break;
                }

                if (line.isBlank()) {
                    continue;
                }

                log.debug("Received: {}", line);

                try {
                    String response = protocolHandler.processMessage(line);
                    if (response != null) {
                        writer.println(response);
                        writer.flush();
                        log.debug("Sent: {}", response);
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
        log.info("Stop requested");
        running = false;
    }
}
