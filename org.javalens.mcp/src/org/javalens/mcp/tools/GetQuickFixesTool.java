package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get available quick fixes for a problem at a specific position.
 *
 * Maps IProblem IDs to available fixes like add import, remove unused import,
 * add throws declaration, or surround with try-catch.
 */
public class GetQuickFixesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetQuickFixesTool.class);

    // Common IProblem IDs we can provide fixes for
    private static final int UNDEFINED_TYPE = 16777218;           // IProblem.UndefinedType
    private static final int UNDEFINED_NAME = 16777219;           // IProblem.UndefinedName
    private static final int UNUSED_IMPORT = 268435844;           // IProblem.UnusedImport
    private static final int UNHANDLED_EXCEPTION = 16777384;      // IProblem.UnhandledException
    private static final int IMPORT_NOT_FOUND = 268435846;        // IProblem.ImportNotFound

    public GetQuickFixesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_quick_fixes";
    }

    @Override
    public String getDescription() {
        return """
            List available fixes for a problem at position.

            USAGE: get_quick_fixes(filePath="...", line=10)
            OUTPUT: List of quick fixes with fixId, label, and category

            Supported fixes:
            - UndefinedType: Suggest imports for unresolved types
            - UnusedImport: Remove unused import
            - UnhandledException: Add throws or surround with try-catch

            IMPORTANT: Uses ZERO-BASED line numbers.

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
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number (optional, uses whole line if omitted)"
            )
        ));
        schema.put("required", List.of("filePath", "line"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Required and must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Reconcile to get problems
            CompilationUnit ast = cu.reconcile(
                AST.getJLSLatest(),
                ICompilationUnit.FORCE_PROBLEM_DETECTION,
                null,
                null
            );

            if (ast == null) {
                return ToolResponse.success(Map.of(
                    "filePath", service.getPathUtils().formatPath(path),
                    "line", line,
                    "problemCount", 0,
                    "fixes", List.of()
                ));
            }

            IProblem[] problems = ast.getProblems();
            List<Map<String, Object>> fixes = new ArrayList<>();
            List<Map<String, Object>> problemsAtLine = new ArrayList<>();

            // Filter problems at the specified line
            for (IProblem problem : problems) {
                int problemLine = problem.getSourceLineNumber() - 1; // Convert to 0-based
                if (problemLine != line) continue;

                // If column specified, check if problem covers that column
                if (column >= 0) {
                    int startOffset = problem.getSourceStart();
                    int endOffset = problem.getSourceEnd();
                    int startCol = ast.getColumnNumber(startOffset);
                    int endCol = ast.getColumnNumber(endOffset);
                    if (column < startCol || column > endCol) continue;
                }

                Map<String, Object> problemInfo = new LinkedHashMap<>();
                problemInfo.put("problemId", problem.getID());
                problemInfo.put("message", problem.getMessage());
                problemInfo.put("severity", problem.isError() ? "error" : "warning");
                problemsAtLine.add(problemInfo);

                // Generate fixes based on problem type
                List<Map<String, Object>> problemFixes = generateFixes(
                    problem, ast, cu, service);
                fixes.addAll(problemFixes);
            }

            // Sort fixes by relevance
            fixes.sort(Comparator.comparingInt(
                (Map<String, Object> f) -> (int) f.get("relevance")).reversed());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("line", line);
            data.put("problemCount", problemsAtLine.size());
            data.put("problems", problemsAtLine);
            data.put("fixes", fixes);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(fixes.size())
                .returnedCount(fixes.size())
                .suggestedNextTools(List.of(
                    "apply_quick_fix with fixId to apply a fix",
                    "get_diagnostics to see all problems"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting quick fixes: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Generate fixes for a specific problem.
     */
    private List<Map<String, Object>> generateFixes(IProblem problem, CompilationUnit ast,
                                                     ICompilationUnit cu, IJdtService service) {
        List<Map<String, Object>> fixes = new ArrayList<>();
        int problemId = problem.getID();

        try {
            if (problemId == UNDEFINED_TYPE || problemId == UNDEFINED_NAME) {
                // Extract the unresolved type name from the problem arguments
                String[] args = problem.getArguments();
                String unresolvedName = null;

                if (args != null && args.length > 0) {
                    unresolvedName = args[0];
                } else {
                    // Try to extract from source
                    int start = problem.getSourceStart();
                    int end = problem.getSourceEnd();
                    if (start >= 0 && end >= start) {
                        unresolvedName = cu.getSource().substring(start, end + 1);
                    }
                }

                if (unresolvedName != null) {
                    List<Map<String, Object>> importFixes = suggestImportFixes(
                        unresolvedName, service, problem);
                    fixes.addAll(importFixes);
                }
            }

            if (problemId == UNUSED_IMPORT) {
                // Find the import index
                @SuppressWarnings("unchecked")
                List<ImportDeclaration> imports = ast.imports();
                int importIndex = -1;

                for (int i = 0; i < imports.size(); i++) {
                    ImportDeclaration imp = imports.get(i);
                    if (imp.getStartPosition() <= problem.getSourceStart() &&
                        imp.getStartPosition() + imp.getLength() >= problem.getSourceEnd()) {
                        importIndex = i;
                        break;
                    }
                }

                if (importIndex >= 0) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("fixId", "remove_import:" + importIndex);
                    fix.put("label", "Remove unused import");
                    fix.put("category", "IMPORT");
                    fix.put("relevance", 90);
                    fix.put("problemId", problemId);
                    fixes.add(fix);
                }
            }

            if (problemId == UNHANDLED_EXCEPTION) {
                // Extract exception type from problem arguments
                String[] args = problem.getArguments();
                String exceptionType = args != null && args.length > 0 ? args[0] : "Exception";

                // Fix 1: Add throws declaration
                Map<String, Object> addThrowsFix = new LinkedHashMap<>();
                addThrowsFix.put("fixId", "add_throws:" + exceptionType);
                addThrowsFix.put("label", "Add throws declaration for " + exceptionType);
                addThrowsFix.put("category", "EXCEPTION");
                addThrowsFix.put("relevance", 80);
                addThrowsFix.put("problemId", problemId);
                fixes.add(addThrowsFix);

                // Fix 2: Surround with try-catch
                Map<String, Object> tryCatchFix = new LinkedHashMap<>();
                tryCatchFix.put("fixId", "surround_try_catch:" + exceptionType);
                tryCatchFix.put("label", "Surround with try-catch for " + exceptionType);
                tryCatchFix.put("category", "EXCEPTION");
                tryCatchFix.put("relevance", 75);
                tryCatchFix.put("problemId", problemId);
                fixes.add(tryCatchFix);
            }

            if (problemId == IMPORT_NOT_FOUND) {
                // Suggest removing the bad import
                @SuppressWarnings("unchecked")
                List<ImportDeclaration> imports = ast.imports();
                int importIndex = -1;

                for (int i = 0; i < imports.size(); i++) {
                    ImportDeclaration imp = imports.get(i);
                    if (imp.getStartPosition() <= problem.getSourceStart() &&
                        imp.getStartPosition() + imp.getLength() >= problem.getSourceEnd()) {
                        importIndex = i;
                        break;
                    }
                }

                if (importIndex >= 0) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("fixId", "remove_import:" + importIndex);
                    fix.put("label", "Remove unresolved import");
                    fix.put("category", "IMPORT");
                    fix.put("relevance", 85);
                    fix.put("problemId", problemId);
                    fixes.add(fix);
                }
            }

        } catch (Exception e) {
            log.debug("Error generating fixes for problem {}: {}", problemId, e.getMessage());
        }

        return fixes;
    }

    /**
     * Suggest import fixes for an unresolved type.
     */
    private List<Map<String, Object>> suggestImportFixes(String typeName, IJdtService service,
                                                          IProblem problem) {
        List<Map<String, Object>> fixes = new ArrayList<>();

        try {
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                new IJavaElement[] { service.getJavaProject() },
                IJavaSearchScope.SOURCES |
                IJavaSearchScope.APPLICATION_LIBRARIES |
                IJavaSearchScope.SYSTEM_LIBRARIES
            );

            SearchEngine engine = new SearchEngine();
            List<Map<String, Object>> candidates = new ArrayList<>();

            TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
                @Override
                public void acceptTypeNameMatch(TypeNameMatch match) {
                    if (candidates.size() >= 10) return;

                    try {
                        IType type = match.getType();
                        String fqn = type.getFullyQualifiedName();
                        String packageName = type.getPackageFragment().getElementName();

                        // Skip internal packages
                        if (packageName.contains(".internal.") ||
                            packageName.startsWith("sun.") ||
                            packageName.startsWith("com.sun.")) {
                            return;
                        }

                        int relevance = calculateRelevance(packageName);

                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("fqn", fqn);
                        candidate.put("relevance", relevance);
                        candidates.add(candidate);
                    } catch (Exception e) {
                        // Skip this match
                    }
                }
            };

            engine.searchAllTypeNames(
                null,
                SearchPattern.R_EXACT_MATCH,
                typeName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                requestor,
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                new NullProgressMonitor()
            );

            // Sort by relevance
            candidates.sort(Comparator.comparingInt(
                (Map<String, Object> c) -> (int) c.get("relevance")).reversed());

            // Create fixes
            for (Map<String, Object> candidate : candidates) {
                String fqn = (String) candidate.get("fqn");
                int relevance = (int) candidate.get("relevance");

                String simplePkg = fqn.substring(0, fqn.lastIndexOf('.'));

                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("fixId", "add_import:" + fqn);
                fix.put("label", "Import '" + typeName + "' (" + simplePkg + ")");
                fix.put("category", "IMPORT");
                fix.put("relevance", relevance);
                fix.put("problemId", problem.getID());
                fixes.add(fix);
            }

        } catch (Exception e) {
            log.debug("Error suggesting imports for '{}': {}", typeName, e.getMessage());
        }

        return fixes;
    }

    private int calculateRelevance(String packageName) {
        if (packageName.equals("java.util")) return 100;
        if (packageName.startsWith("java.util.")) return 95;
        if (packageName.equals("java.io")) return 90;
        if (packageName.startsWith("java.io.")) return 85;
        if (packageName.startsWith("java.nio.")) return 85;
        if (packageName.startsWith("java.time")) return 85;
        if (packageName.startsWith("java.")) return 70;
        if (packageName.startsWith("javax.")) return 65;
        if (packageName.startsWith("org.springframework.")) return 60;
        if (packageName.startsWith("org.junit.")) return 60;
        return 40;
    }
}
