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
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get compilation diagnostics (errors and warnings) for a file or project.
 *
 * Uses Eclipse JDT's reconcile mechanism to get IProblem[] with compiler errors/warnings.
 */
public class GetDiagnosticsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetDiagnosticsTool.class);

    /** The accepted {@code severity} values, compared case-insensitively. */
    private static final List<String> SEVERITIES = List.of("error", "warning", "all");

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

            severity (optional, compared case-insensitively): error or warning keeps
            the diagnostics of that severity; all (the default) keeps both. Any
            other value is rejected with INVALID_PARAMETER.

            Every file is checked and every diagnostic kept is counted in
            totalDiagnostics, errorCount and warningCount; maxResults limits only
            the diagnostics listed, and meta.truncated says when the list is cut.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("filePath", "string", "Optional path to source file. If omitted, checks all files.")
            .optional("severity", "string", "Filter by severity, case-insensitive: 'error', 'warning' "
                + "or 'all' (default: 'all')")
            .optional("maxResults", "integer", "Max diagnostics to return (default 100)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String requestedSeverity = getStringParam(arguments, "severity", "all");
        String severity = requestedSeverity.toLowerCase(Locale.ROOT);
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (!SEVERITIES.contains(severity)) {
            return ToolResponse.invalidParameter("severity",
                "Must be one of " + String.join(", ", SEVERITIES) + " (case-insensitive); got: "
                    + requestedSeverity);
        }
        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }

        try {
            // Every file is checked and every diagnostic counted; maxResults cuts the list only.
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
                DiagnosticCounts counts = collectDiagnostics(service, cu, path, severity, diagnostics);
                errorCount = counts.errors;
                warningCount = counts.warnings;
            } else {
                // Check all files in the project
                for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
                    if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;

                    for (IJavaElement child : root.getChildren()) {
                        if (!(child instanceof IPackageFragment pkg)) continue;

                        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                            filesChecked++;

                            Path cuPath = getCompilationUnitPath(cu);
                            DiagnosticCounts counts = collectDiagnostics(service, cu, cuPath, severity, diagnostics);
                            errorCount += counts.errors;
                            warningCount += counts.warnings;
                        }
                    }
                }
            }

            int total = diagnostics.size();
            boolean truncated = total > maxResults;
            List<Map<String, Object>> listed = truncated ? new ArrayList<>(diagnostics.subList(0, maxResults)) : diagnostics;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalDiagnostics", total);
            data.put("errorCount", errorCount);
            data.put("warningCount", warningCount);
            data.put("filesChecked", filesChecked);
            data.put("diagnostics", listed);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(listed.size())
                .truncated(truncated)
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
        if (cu.getResource() != null && cu.getResource().getLocation() != null) {
            return Path.of(cu.getResource().getLocation().toOSString());
        }
        return Path.of(cu.getElementName());
    }

    /**
     * Adds every error and warning of {@code cu} that {@code severity} keeps to {@code diagnostics}
     * and counts them.
     *
     * @throws IllegalStateException when JDT reconciles {@code cu} to no syntax tree: its
     *     diagnostics cannot be read, and answering none would claim the file is clean
     * @throws JavaModelException when JDT cannot reconcile {@code cu}: no diagnostic of its own
     *     stands for that
     */
    private DiagnosticCounts collectDiagnostics(IJdtService service, ICompilationUnit cu, Path path,
                                                  String severity, List<Map<String, Object>> diagnostics)
            throws JavaModelException {
        int errorCount = 0;
        int warningCount = 0;

        // cu.reconcile(... FORCE_PROBLEM_DETECTION ...) only surfaces problems if the
        // CU is in working-copy mode; otherwise it returns null and every IProblem
        // (errors and warnings) is silently invisible to this tool.
        boolean wasWorkingCopy = cu.isWorkingCopy();
        CompilationUnit ast;
        try {
            // Inside the try: JDT registers the working copy before its first cancellation
            // check (BecomeWorkingCopyOperation :45, Openable :240), so a cancelled call
            // still leaves one, which the finally discards.
            if (!wasWorkingCopy) {
                cu.becomeWorkingCopy(Deadline.monitor());
            }
            ast = cu.reconcile(
                AST.getJLSLatest(),
                ICompilationUnit.FORCE_PROBLEM_DETECTION,
                null,
                Deadline.monitor()
            );
        } finally {
            if (!wasWorkingCopy) {
                cu.discardWorkingCopy();
            }
        }

        if (ast == null) {
            throw new IllegalStateException("JDT produced no syntax tree for "
                + service.getPathUtils().formatPath(path));
        }

        IProblem[] problems = ast.getProblems();

        for (IProblem problem : problems) {
            boolean isError = problem.isError();
            boolean isWarning = problem.isWarning();

            // This tool's contract is errors and warnings only. JDT can report
            // problems at other severities (e.g. info), which must not be surfaced
            // or counted here; otherwise totalDiagnostics would exceed
            // errorCount + warningCount and the severity label would be wrong.
            if (!isError && !isWarning) continue;

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
