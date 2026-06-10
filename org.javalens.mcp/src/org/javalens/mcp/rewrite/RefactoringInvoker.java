package org.javalens.mcp.rewrite;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.refactoring.descriptors.JavaRefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.javalens.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a JDT refactoring (constructed from its public descriptor) headlessly
 * and converts the resulting {@link Change} tree into the edit JSON JavaLens
 * tools return. The change is NEVER performed — JavaLens analyzes and returns
 * edits as text; the caller is the only writer.
 *
 * <p>Status policy: a FATAL condition (or a failure to construct the
 * refactoring) is a refusal carrying JDT's own messages; ERROR and below are
 * surfaced as warnings alongside the computed edits, matching how the IDE lets
 * a user proceed past non-fatal findings.
 */
public final class RefactoringInvoker {

    private static final Logger log = LoggerFactory.getLogger(RefactoringInvoker.class);

    private RefactoringInvoker() {
    }

    /** The outcome: either a refusal with reasons, or edits + warnings. */
    public record Outcome(
        boolean refused,
        List<String> reasons,
        List<String> warnings,
        Map<String, List<Map<String, Object>>> editsByFile,
        List<Map<String, String>> createdFiles,
        List<Map<String, String>> fileRenames) {

        public static Outcome refusal(List<String> reasons) {
            return new Outcome(true, reasons, List.of(), Map.of(), List.of(), List.of());
        }
    }

    public static Outcome run(JavaRefactoringDescriptor descriptor, IJdtService service)
            throws Exception {
        HeadlessJdtEnvironment.ensure();

        RefactoringStatus createStatus = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(createStatus);
        if (refactoring == null || createStatus.hasFatalError()) {
            return Outcome.refusal(messagesOf(createStatus, RefactoringStatus.FATAL));
        }

        RefactoringStatus conditions = refactoring.checkAllConditions(new NullProgressMonitor());
        if (conditions.hasFatalError()) {
            return Outcome.refusal(messagesOf(conditions, RefactoringStatus.FATAL));
        }
        List<String> warnings = new ArrayList<>(messagesOf(createStatus, RefactoringStatus.INFO));
        warnings.addAll(messagesOf(conditions, RefactoringStatus.INFO));

        Change change = refactoring.createChange(new NullProgressMonitor());

        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        List<Map<String, String>> createdFiles = new ArrayList<>();
        List<Map<String, String>> fileRenames = new ArrayList<>();
        convert(change, service, editsByFile, createdFiles, fileRenames);

        return new Outcome(false, List.of(), warnings, editsByFile, createdFiles, fileRenames);
    }

    private static List<String> messagesOf(RefactoringStatus status, int minSeverity) {
        List<String> messages = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            if (entry.getSeverity() >= minSeverity) {
                messages.add(entry.getMessage());
            }
        }
        return messages;
    }

    private static void convert(Change change, IJdtService service,
                                Map<String, List<Map<String, Object>>> editsByFile,
                                List<Map<String, String>> createdFiles,
                                List<Map<String, String>> fileRenames) throws Exception {
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                convert(child, service, editsByFile, createdFiles, fileRenames);
            }
            return;
        }

        String changeClass = change.getClass().getSimpleName();

        // A new compilation unit: surface its path and full content.
        if (changeClass.startsWith("Create") && change instanceof TextEditBasedChange created) {
            Map<String, String> file = new LinkedHashMap<>();
            file.put("filePath", describeModifiedElement(change, service));
            file.put("content", created.getPreviewContent(new NullProgressMonitor()));
            createdFiles.add(file);
            return;
        }

        // Text edits against an existing compilation unit. JDT refactorings
        // emit edit trees that can include copy/move edits whose semantics a
        // flattener cannot soundly reproduce, so the conversion is diff-based:
        // the change's preview content against the current source, reduced to
        // one minimal-span replace edit per file.
        if (change instanceof TextEditBasedChange textChange
                && change.getModifiedElement() instanceof IJavaElement element) {
            ICompilationUnit cu = element instanceof ICompilationUnit unit
                ? unit
                : (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu != null) {
                String source = cu.getSource();
                String preview = textChange.getPreviewContent(new NullProgressMonitor());
                if (preview.equals(source)) {
                    return;
                }
                // Parse without bindings: only line/column mapping is needed.
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(false);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);

                String filePath = formatCuPath(cu, service);
                editsByFile.computeIfAbsent(filePath, k -> new ArrayList<>())
                    .add(minimalReplace(source, preview, ast));
                return;
            }
        }

        // File/CU renames: surface as {from, to}.
        if (changeClass.contains("Rename")) {
            Map<String, String> rename = new LinkedHashMap<>();
            rename.put("from", describeModifiedElement(change, service));
            rename.put("to", change.getName());
            fileRenames.add(rename);
            return;
        }

        // Never silently drop a change we don't understand — the result would
        // be an incomplete refactoring presented as complete.
        throw new IllegalStateException("Unsupported change type in refactoring result: "
            + change.getClass().getName() + " (" + change.getName() + ")");
    }

    /**
     * Reduces an old-source/new-source pair to one replace edit spanning the
     * changed region (common prefix and suffix trimmed).
     */
    private static Map<String, Object> minimalReplace(String source, String preview,
                                                      CompilationUnit ast) {
        int prefix = 0;
        int maxPrefix = Math.min(source.length(), preview.length());
        while (prefix < maxPrefix && source.charAt(prefix) == preview.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        int maxSuffix = Math.min(source.length(), preview.length()) - prefix;
        while (suffix < maxSuffix
                && source.charAt(source.length() - 1 - suffix)
                    == preview.charAt(preview.length() - 1 - suffix)) {
            suffix++;
        }
        int start = prefix;
        int end = source.length() - suffix;

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "replace");
        edit.put("startLine", ast.getLineNumber(start) - 1);
        edit.put("startColumn", ast.getColumnNumber(start));
        edit.put("endLine", ast.getLineNumber(end) - 1);
        edit.put("endColumn", ast.getColumnNumber(end));
        edit.put("startOffset", start);
        edit.put("endOffset", end);
        edit.put("oldText", source.substring(start, end));
        edit.put("newText", preview.substring(prefix, preview.length() - suffix));
        return edit;
    }

    private static String describeModifiedElement(Change change, IJdtService service) {
        Object element = change.getModifiedElement();
        if (element instanceof ICompilationUnit cu) {
            return formatCuPath(cu, service);
        }
        if (element instanceof IJavaElement javaElement) {
            ICompilationUnit cu =
                (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu != null) {
                return formatCuPath(cu, service);
            }
            return javaElement.getElementName();
        }
        return change.getName();
    }

    private static String formatCuPath(ICompilationUnit cu, IJdtService service) {
        try {
            if (cu.getResource() != null && cu.getResource().getLocation() != null) {
                return service.getPathUtils().formatPath(
                    Path.of(cu.getResource().getLocation().toOSString()));
            }
        } catch (Exception e) {
            log.debug("Could not resolve path for {}: {}", cu.getElementName(), e.getMessage());
        }
        return cu.getElementName();
    }
}
