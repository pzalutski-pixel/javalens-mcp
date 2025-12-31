package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Quick syntax-only validation for a file or inline code.
 *
 * Uses ASTParser with setResolveBindings(false) for fast syntax-only checking.
 * Much faster than get_diagnostics since no semantic analysis is performed.
 */
public class ValidateSyntaxTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ValidateSyntaxTool.class);

    public ValidateSyntaxTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "validate_syntax";
    }

    @Override
    public String getDescription() {
        return """
            Quick syntax-only validation for a file or inline code.

            USAGE: validate_syntax(filePath="...") or validate_syntax(content="...")
            OUTPUT: Syntax errors (no semantic analysis for speed)

            Much faster than get_diagnostics - use for quick syntax checks.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file to validate"
            ),
            "content", Map.of(
                "type", "string",
                "description", "Inline Java source code to validate (alternative to filePath)"
            ),
            "fileName", Map.of(
                "type", "string",
                "description", "Optional filename for inline content (default: Untitled.java)"
            )
        ));
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String content = getStringParam(arguments, "content");
        String fileName = getStringParam(arguments, "fileName", "Untitled.java");

        // Must provide either filePath or content
        if ((filePath == null || filePath.isBlank()) && (content == null || content.isBlank())) {
            return ToolResponse.invalidParameter("filePath/content", "Must provide either filePath or content");
        }

        try {
            String sourceCode;
            String unitName;

            if (filePath != null && !filePath.isBlank()) {
                // Read from file
                Path path = Path.of(filePath);
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    return ToolResponse.fileNotFound(filePath);
                }
                sourceCode = cu.getSource();
                unitName = cu.getElementName();
            } else {
                // Use inline content
                sourceCode = content;
                unitName = fileName;
            }

            // Parse with syntax-only (no binding resolution)
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(sourceCode.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false); // Syntax only - much faster!
            parser.setUnitName(unitName);

            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            IProblem[] problems = ast.getProblems();

            // Collect syntax errors only
            List<Map<String, Object>> syntaxErrors = new ArrayList<>();
            for (IProblem problem : problems) {
                // Only include errors (not warnings) for syntax validation
                if (!problem.isError()) continue;

                Map<String, Object> error = new LinkedHashMap<>();
                error.put("line", problem.getSourceLineNumber() - 1); // 0-based
                error.put("column", ast.getColumnNumber(problem.getSourceStart()));
                error.put("startOffset", problem.getSourceStart());
                error.put("endOffset", problem.getSourceEnd());
                error.put("message", problem.getMessage());
                error.put("problemId", problem.getID());

                syntaxErrors.add(error);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("valid", syntaxErrors.isEmpty());
            data.put("fileName", unitName);
            data.put("errorCount", syntaxErrors.size());
            data.put("errors", syntaxErrors);

            List<String> nextTools = new ArrayList<>();
            if (!syntaxErrors.isEmpty()) {
                nextTools.add("get_diagnostics for detailed semantic errors");
            } else {
                nextTools.add("get_diagnostics for type checking");
                nextTools.add("get_document_symbols to explore structure");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(nextTools)
                .build());

        } catch (JavaModelException e) {
            log.error("Error reading file: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        } catch (Exception e) {
            log.error("Error validating syntax: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
