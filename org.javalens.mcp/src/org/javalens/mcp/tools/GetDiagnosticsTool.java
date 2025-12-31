package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
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
 * Get compilation diagnostics (errors and warnings) for a file or project.
 *
 * Uses Eclipse JDT's reconcile mechanism to get IProblem[] with compiler errors/warnings.
 */
public class GetDiagnosticsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetDiagnosticsTool.class);

    public GetDiagnosticsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_diagnostics";
    }

    @Override
    public String getDescription() {
        return """
            Get compilation diagnostics (errors and warnings) for a file or project.

            USAGE: get_diagnostics() for all files, or get_diagnostics(filePath="...") for one file
            OUTPUT: List of compilation errors and warnings with locations

            Useful for finding syntax errors, type mismatches, missing imports, etc.

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
                "description", "Optional path to source file. If omitted, checks all files."
            ),
            "severity", Map.of(
                "type", "string",
                "description", "Filter by severity: 'error', 'warning', or 'all' (default: 'all')"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max diagnostics to return (default 100)"
            )
        ));
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String severity = getStringParam(arguments, "severity", "all");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        try {
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            int errorCount = 0;
            int warningCount = 0;
            int filesChecked = 0;

            if (filePath != null && !filePath.isBlank()) {
                // Check single file
                Path path = Path.of(filePath);
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    return ToolResponse.fileNotFound(filePath);
                }
                filesChecked = 1;
                DiagnosticCounts counts = collectDiagnostics(service, cu, path, severity, maxResults, diagnostics);
                errorCount = counts.errors;
                warningCount = counts.warnings;
            } else {
                // Check all files in the project
                for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
                    if (diagnostics.size() >= maxResults) break;
                    if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

                    for (IJavaElement child : root.getChildren()) {
                        if (diagnostics.size() >= maxResults) break;
                        if (!(child instanceof IPackageFragment pkg)) continue;

                        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                            if (diagnostics.size() >= maxResults) break;
                            filesChecked++;

                            Path cuPath = getCompilationUnitPath(cu);
                            DiagnosticCounts counts = collectDiagnostics(service, cu, cuPath, severity,
                                maxResults - diagnostics.size(), diagnostics);
                            errorCount += counts.errors;
                            warningCount += counts.warnings;
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalDiagnostics", diagnostics.size());
            data.put("errorCount", errorCount);
            data.put("warningCount", warningCount);
            data.put("filesChecked", filesChecked);
            data.put("diagnostics", diagnostics);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(diagnostics.size())
                .returnedCount(diagnostics.size())
                .truncated(diagnostics.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "go_to_definition to navigate to error location",
                    "get_document_symbols to see file structure"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting diagnostics: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Path getCompilationUnitPath(ICompilationUnit cu) {
        try {
            if (cu.getResource() != null && cu.getResource().getLocation() != null) {
                return Path.of(cu.getResource().getLocation().toOSString());
            }
        } catch (Exception e) {
            log.trace("Error getting CU path: {}", e.getMessage());
        }
        return Path.of(cu.getElementName());
    }

    private DiagnosticCounts collectDiagnostics(IJdtService service, ICompilationUnit cu, Path path,
                                                  String severity, int remaining,
                                                  List<Map<String, Object>> diagnostics) {
        int errorCount = 0;
        int warningCount = 0;

        try {
            // Reconcile to get AST with problems
            CompilationUnit ast = cu.reconcile(
                AST.getJLSLatest(),
                ICompilationUnit.FORCE_PROBLEM_DETECTION,
                null,
                null
            );

            if (ast == null) {
                log.debug("No AST returned for {}", path);
                return new DiagnosticCounts(0, 0);
            }

            IProblem[] problems = ast.getProblems();

            for (IProblem problem : problems) {
                if (diagnostics.size() >= diagnostics.size() + remaining) break;

                boolean isError = problem.isError();
                boolean isWarning = problem.isWarning();

                // Filter by severity
                if ("error".equals(severity) && !isError) continue;
                if ("warning".equals(severity) && !isWarning) continue;

                if (isError) errorCount++;
                if (isWarning) warningCount++;

                Map<String, Object> diag = new LinkedHashMap<>();
                diag.put("filePath", service.getPathUtils().formatPath(path));
                diag.put("line", problem.getSourceLineNumber() - 1); // Convert to 0-based
                diag.put("startOffset", problem.getSourceStart());
                diag.put("endOffset", problem.getSourceEnd());
                diag.put("severity", isError ? "error" : "warning");
                diag.put("message", problem.getMessage());
                diag.put("problemId", problem.getID());
                diag.put("category", categorizeProblem(problem));

                diagnostics.add(diag);
            }
        } catch (JavaModelException e) {
            log.debug("Error reconciling {}: {}", path, e.getMessage());
            // Add parse error as diagnostic
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("filePath", service.getPathUtils().formatPath(path));
            diag.put("line", 0);
            diag.put("severity", "error");
            diag.put("message", "Failed to analyze file: " + e.getMessage());
            diag.put("category", "PARSE_ERROR");
            diagnostics.add(diag);
            errorCount++;
        }

        return new DiagnosticCounts(errorCount, warningCount);
    }

    /**
     * Categorize a problem using JDT's categorization system.
     */
    private String categorizeProblem(IProblem problem) {
        // CategorizedProblem provides richer categorization
        if (problem instanceof CategorizedProblem categorized) {
            return categoryIdToName(categorized.getCategoryID());
        }

        // Fall back to IProblem bit flag checking
        int id = problem.getID();

        if ((id & IProblem.Syntax) != 0) return "SYNTAX";
        if ((id & IProblem.ImportRelated) != 0) return "IMPORT";
        if ((id & IProblem.TypeRelated) != 0) return "TYPE";
        if ((id & IProblem.MethodRelated) != 0) return "METHOD";
        if ((id & IProblem.ConstructorRelated) != 0) return "CONSTRUCTOR";
        if ((id & IProblem.FieldRelated) != 0) return "FIELD";
        if ((id & IProblem.Internal) != 0) return "INTERNAL";

        return "OTHER";
    }

    private String categoryIdToName(int categoryId) {
        return switch (categoryId) {
            case CategorizedProblem.CAT_UNSPECIFIED -> "UNSPECIFIED";
            case CategorizedProblem.CAT_BUILDPATH -> "BUILDPATH";
            case CategorizedProblem.CAT_SYNTAX -> "SYNTAX";
            case CategorizedProblem.CAT_IMPORT -> "IMPORT";
            case CategorizedProblem.CAT_TYPE -> "TYPE";
            case CategorizedProblem.CAT_MEMBER -> "MEMBER";
            case CategorizedProblem.CAT_INTERNAL -> "INTERNAL";
            case CategorizedProblem.CAT_JAVADOC -> "JAVADOC";
            case CategorizedProblem.CAT_CODE_STYLE -> "CODE_STYLE";
            case CategorizedProblem.CAT_POTENTIAL_PROGRAMMING_PROBLEM -> "POTENTIAL_PROBLEM";
            case CategorizedProblem.CAT_NAME_SHADOWING_CONFLICT -> "NAME_SHADOWING";
            case CategorizedProblem.CAT_DEPRECATION -> "DEPRECATION";
            case CategorizedProblem.CAT_UNNECESSARY_CODE -> "UNNECESSARY_CODE";
            case CategorizedProblem.CAT_UNCHECKED_RAW -> "UNCHECKED_RAW";
            case CategorizedProblem.CAT_NLS -> "NLS";
            case CategorizedProblem.CAT_RESTRICTION -> "RESTRICTION";
            case CategorizedProblem.CAT_MODULE -> "MODULE";
            case CategorizedProblem.CAT_COMPLIANCE -> "COMPLIANCE";
            case CategorizedProblem.CAT_PREVIEW_RELATED -> "PREVIEW";
            default -> "OTHER";
        };
    }

    private record DiagnosticCounts(int errors, int warnings) {}
}
