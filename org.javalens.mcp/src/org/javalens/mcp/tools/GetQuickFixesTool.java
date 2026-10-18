package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.javalens.core.Deadline;
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

    /** The import candidates listed for one unresolved type, the most relevant. */
    private static final int MAX_IMPORT_CANDIDATES = 10;

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
            - UndefinedType: Suggest imports for unresolved types, each fixId
              naming the type as an import does (add_import:pkg.Outer.Inner for a
              member type); a type in the default package, or in a package its
              module does not export to the project (such as the JDK's internal
              packages), cannot be imported and is never suggested; the package's
              name never decides. Every candidate is ranked by relevance
              (equally relevant ones by name), then the 10 most relevant are listed
            - UnusedImport: Remove unused import
            - UnhandledException: Add throws or surround with try-catch

            Fixes are listed by relevance. meta.totalCount counts every fix for the
            problems at the position, meta.returnedCount those listed, and
            meta.truncated is true when import candidates are left out.

            IMPORTANT: Uses ZERO-BASED line numbers.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .optional("column", "integer", "Zero-based column number (optional, uses whole line if omitted)")
            .build();
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

            // cu.reconcile(... FORCE_PROBLEM_DETECTION ...) only works on a working-copy
            // ICompilationUnit. Without working-copy mode, reconcile returns null and
            // every problem (warnings, errors) is silently invisible to this tool. So we
            // put the CU into working-copy mode for the duration of the reconcile.
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

            // With no syntax tree the problems cannot be read; answering none would claim the
            // line has no problem.
            if (ast == null) {
                throw new IllegalStateException("JDT produced no syntax tree for "
                    + service.getPathUtils().formatPath(path));
            }

            IProblem[] problems = ast.getProblems();
            List<Map<String, Object>> fixes = new ArrayList<>();
            int totalFixes = 0;
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
                Fixes problemFixes = generateFixes(problem, ast, cu, service);
                fixes.addAll(problemFixes.listed());
                totalFixes += problemFixes.total();
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
                .totalCount(totalFixes)
                .returnedCount(fixes.size())
                .truncated(fixes.size() < totalFixes)
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

    /** A problem's fixes: those listed, and the count of all of them. */
    private record Fixes(List<Map<String, Object>> listed, int total) {
    }

    /**
     * Generate fixes for a specific problem: every fix, except that an unresolved type lists
     * only its most relevant import candidates.
     */
    private Fixes generateFixes(IProblem problem, CompilationUnit ast,
                                ICompilationUnit cu, IJdtService service) throws CoreException {
        List<Map<String, Object>> fixes = new ArrayList<>();
        int unlisted = 0;
        int problemId = problem.getID();

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
                int listed = Math.min(MAX_IMPORT_CANDIDATES, importFixes.size());
                fixes.addAll(importFixes.subList(0, listed));
                unlisted += importFixes.size() - listed;
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

        return new Fixes(fixes, fixes.size() + unlisted);
    }

    /**
     * Every import fix for an unresolved type, one per type the project can import
     * ({@link ImportCandidates}), ranked: by relevance (descending), then by the name the import
     * gives.
     */
    private List<Map<String, Object>> suggestImportFixes(String typeName, IJdtService service,
                                                          IProblem problem) throws CoreException {
        List<Map<String, Object>> fixes = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();

        for (IType type : ImportCandidates.of(service.getJavaProject(), typeName)) {
            String packageName = type.getPackageFragment().getElementName();

            Map<String, Object> candidate = new LinkedHashMap<>();
            // An import names a member type by its canonical name, dots between
            // the enclosing type and the member type (JLS 6.7, 7.5.1).
            candidate.put("importName", type.getFullyQualifiedName('.'));
            candidate.put("packageName", packageName);
            candidate.put("relevance", calculateRelevance(packageName));
            candidates.add(candidate);
        }

        // Rank every candidate: by relevance (descending), then by import name
        candidates.sort(Comparator.comparingInt((Map<String, Object> c) -> (int) c.get("relevance"))
            .reversed()
            .thenComparing(c -> (String) c.get("importName")));

        // Create fixes
        for (Map<String, Object> candidate : candidates) {
            int relevance = (int) candidate.get("relevance");

            Map<String, Object> fix = new LinkedHashMap<>();
            fix.put("fixId", "add_import:" + candidate.get("importName"));
            fix.put("label", "Import '" + typeName + "' (" + candidate.get("packageName") + ")");
            fix.put("category", "IMPORT");
            fix.put("relevance", relevance);
            fix.put("problemId", problem.getID());
            fixes.add(fix);
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
