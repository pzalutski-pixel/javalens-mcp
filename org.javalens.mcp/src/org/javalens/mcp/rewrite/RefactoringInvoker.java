package org.javalens.mcp.rewrite;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.refactoring.descriptors.JavaRefactoringDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaStatusContext;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a JDT refactoring (constructed from its public descriptor) headlessly
 * and converts the resulting {@link Change} tree into the edit JSON JavaLens
 * tools return. The change is NEVER performed: JavaLens analyzes and returns
 * edits as text; the caller is the only writer.
 *
 * <p>Status policy of {@link #run}: a FATAL condition (or a failure to construct
 * the refactoring) is a refusal carrying JDT's own messages; ERROR and below are
 * surfaced as warnings alongside the computed edits, matching how the IDE lets
 * a user proceed past non-fatal findings. {@link #findings}, which rename_symbol
 * and change_method_signature use, counts ERROR with FATAL as a conflict: a
 * rename JDT marks ERROR leaves the code not compiling or not meaning what it
 * meant.
 */
public final class RefactoringInvoker {

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

    /**
     * Runs the refactoring {@code descriptor} describes, never performing it. Its findings, the
     * refusal's reasons or the warnings, are listed by the place each names in source, its file
     * then its offset, those naming one place, and after all of them those naming none, as JDT
     * reports them, each naming a file as every tool names it. Where JDT reports in the order of a
     * hash set or map of Java elements, whose hashes follow the project's name (the members a moved
     * member uses, ReferenceFinderUtil.extractElements :173-180, read by pull up and extract
     * superclass; the visibility changes, MemberVisibilityAdjustor.rewriteVisibility :1087 over
     * the hash map of adjustments pull up, extract superclass and push down hand it), this order is
     * the same for every load.
     */
    public static Outcome run(JavaRefactoringDescriptor descriptor, IJdtService service)
            throws Exception {
        HeadlessJdtEnvironment.ensure();

        RefactoringStatus createStatus = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(createStatus);
        if (refactoring == null || createStatus.hasFatalError()) {
            return Outcome.refusal(byPlace(entriesOf(createStatus, RefactoringStatus.FATAL), service));
        }

        RefactoringStatus conditions = refactoring.checkAllConditions(Deadline.monitor());
        if (conditions.hasFatalError()) {
            return Outcome.refusal(byPlace(entriesOf(conditions, RefactoringStatus.FATAL), service));
        }
        List<RefactoringStatusEntry> entries = new ArrayList<>(entriesOf(createStatus, RefactoringStatus.INFO));
        entries.addAll(entriesOf(conditions, RefactoringStatus.INFO));
        List<String> warnings = byPlace(entries, service);

        Change change = refactoring.createChange(Deadline.monitor());

        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        List<Map<String, String>> createdFiles = new ArrayList<>();
        List<Map<String, String>> fileRenames = new ArrayList<>();
        convert(change, service, editsByFile, createdFiles, fileRenames);

        return new Outcome(false, List.of(), warnings, editsByFile, createdFiles, fileRenames);
    }

    /**
     * What a rename's conditions find: {@code conflicts}, the FATAL and ERROR entries, where JDT
     * judges the refactoring would leave the code not compiling or not meaning what it meant, and
     * {@code warnings}, the WARNING and INFO entries. Each list runs by the place its entries name,
     * as {@link #run} orders findings, and names a file JDT names by its workspace path as
     * every tool names it ({@link #withProjectPaths}). JDT's note that its search found places it
     * could not confirm (RefactoringSearchEngine.addStatusErrors :219-224) names no conflict: the
     * tools list those places as potentialMatches.
     */
    public record Findings(List<String> conflicts, List<String> warnings) {
    }

    /**
     * The findings of {@code refactoring}'s initial conditions and, when those leave it possible,
     * its final conditions; the refactoring is never performed. {@code beforeFinalConditions} runs
     * between the two, as JDT's wizards set a refactoring's input once its initial conditions have
     * chosen its element (a rename of a method renames from the method it overrides, and resets the
     * new name to that method's own: RenameVirtualMethodProcessor.checkInitialConditions :113-140,
     * RenameMethodProcessor.initialize :147-155).
     */
    public static Findings findings(Refactoring refactoring, Runnable beforeFinalConditions, IJdtService service)
            throws CoreException {
        HeadlessJdtEnvironment.ensure();
        RefactoringStatus conditions = refactoring.checkInitialConditions(Deadline.monitor());
        if (!conditions.hasFatalError()) {
            beforeFinalConditions.run();
            conditions.merge(refactoring.checkFinalConditions(Deadline.monitor()));
        }
        return findings(conditions, service);
    }

    /** The findings {@code status} holds, as {@link Findings} lists them. */
    public static Findings findings(RefactoringStatus status, IJdtService service) {
        List<RefactoringStatusEntry> conflicts = new ArrayList<>();
        List<RefactoringStatusEntry> warnings = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            if (entry.getSeverity() < RefactoringStatus.ERROR) {
                warnings.add(entry);
            } else if (!RefactoringCoreMessages.RefactoringSearchEngine_potential_matches.equals(entry.getMessage())) {
                conflicts.add(entry);
            }
        }
        return new Findings(byPlace(conflicts, service), byPlace(warnings, service));
    }

    /**
     * The messages of {@code entries} by the place each names, its file then its offset, each with
     * the files it names as every tool names them: a stable sort, so entries naming one place, and
     * after all of them those naming none, keep JDT's order.
     */
    private static List<String> byPlace(List<RefactoringStatusEntry> entries, IJdtService service) {
        List<RefactoringStatusEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((RefactoringStatusEntry entry) -> placeOf(entry, service),
            Comparator.nullsLast(Comparator.comparing(Place::file).thenComparingInt(Place::offset))));
        return sorted.stream().map(entry -> withProjectPaths(entry.getMessage(), service)).toList();
    }

    /**
     * {@code message} with each workspace path of a file of the loaded project, as JDT's messages
     * write it ({@code /project/src-0-.../Foo.java}, its leading slash optional), replaced by the
     * file's path as every tool names it: the linked folders and the workspace project are
     * JavaLens's own, never the user's.
     */
    static String withProjectPaths(String message, IJdtService service) {
        String project = service.getJavaProject().getElementName() + "/";
        StringBuilder result = new StringBuilder();
        int from = 0;
        int at;
        while ((at = message.indexOf(project, from)) >= 0) {
            int start = at > 0 && message.charAt(at - 1) == '/' ? at - 1 : at;
            int end = at;
            while (end < message.length() && !Character.isWhitespace(message.charAt(end))
                    && "'\"".indexOf(message.charAt(end)) < 0) {
                end++;
            }
            IResource resource = ResourcesPlugin.getWorkspace().getRoot()
                .findMember(new org.eclipse.core.runtime.Path(message.substring(at, end)));
            if (resource == null || resource.getLocation() == null) {
                throw new IllegalStateException("JDT names a file the workspace does not hold: " + message);
            }
            result.append(message, from, start)
                .append(service.getPathUtils().formatPath(Path.of(resource.getLocation().toOSString())));
            from = end;
        }
        return result.append(message.substring(from)).toString();
    }

    private static List<RefactoringStatusEntry> entriesOf(RefactoringStatus status, int minSeverity) {
        List<RefactoringStatusEntry> entries = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            if (entry.getSeverity() >= minSeverity) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** A place in source: a file as tools name it and an offset in it. */
    private record Place(String file, int offset) {
    }

    /**
     * The place {@code entry} names: the source range of its context in a compilation unit
     * (JavaStatusContext, the context JDT's refactorings give a member, an import or a node), or
     * null when it names none.
     */
    private static Place placeOf(RefactoringStatusEntry entry, IJdtService service) {
        if (entry.getContext() instanceof JavaStatusContext context && !context.isBinary()
                && context.getCompilationUnit() != null && context.getSourceRange() != null) {
            return new Place(formatCuPath(context.getCompilationUnit(), service), context.getSourceRange().getOffset());
        }
        return null;
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

        // A new file: surface its path and full content.
        if (change instanceof org.eclipse.jdt.internal.corext.refactoring.nls.changes.CreateTextFileChange createChange) {
            Map<String, String> file = new LinkedHashMap<>();
            String path = change instanceof
                org.eclipse.jdt.internal.corext.refactoring.changes.CreateCompilationUnitChange cuChange
                ? formatCuPath(cuChange.getCu(), service)
                : createChange.getName();
            file.put("filePath", path);
            file.put("content", createChange.getPreview());
            createdFiles.add(file);
            return;
        }

        // Text edits against an existing compilation unit. JDT refactorings
        // emit edit trees that can include copy/move edits whose semantics a
        // flattener cannot soundly reproduce, so the conversion is diff-based:
        // the change's preview content against the current source, reduced to
        // one minimal-span replace edit per file. The modified element varies
        // by change subclass (the CU, another Java element, or the IFile).
        if (change instanceof TextEditBasedChange textChange) {
            ICompilationUnit cu = resolveCompilationUnit(change.getModifiedElement());
            if (cu != null) {
                String source = cu.getSource();
                // Never cancelled: a compilation unit change becomes a working copy before its
                // monitor's next cancellation check and releases it only once the document is
                // acquired (CompilationUnitChange.acquireDocument :81-85), so a preview cancelled
                // there would leave the unit a working copy. Applying the edits to one unit is
                // bounded.
                String preview = textChange.getPreviewContent(new NullProgressMonitor());
                if (preview.equals(source)) {
                    return;
                }
                // Parse without bindings: only line/column mapping is needed.
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(false);
                CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());

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

        // Never silently drop a change we don't understand: the result would
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

    /** The CU a change targets, from whatever form its modified element takes. */
    private static ICompilationUnit resolveCompilationUnit(Object element) {
        if (element instanceof ICompilationUnit cu) {
            return cu;
        }
        if (element instanceof IJavaElement javaElement) {
            return (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
        }
        if (element instanceof org.eclipse.core.resources.IFile file
                && org.eclipse.jdt.core.JavaCore.create(file) instanceof ICompilationUnit cu) {
            return cu;
        }
        return null;
    }

    private static String describeModifiedElement(Change change, IJdtService service) {
        ICompilationUnit cu = resolveCompilationUnit(change.getModifiedElement());
        if (cu != null) {
            return formatCuPath(cu, service);
        }
        if (change.getModifiedElement() instanceof IJavaElement javaElement) {
            return javaElement.getElementName();
        }
        return change.getName();
    }

    private static String formatCuPath(ICompilationUnit cu, IJdtService service) {
        if (cu.getResource() != null && cu.getResource().getLocation() != null) {
            return service.getPathUtils().formatPath(
                Path.of(cu.getResource().getLocation().toOSString()));
        }
        return cu.getElementName();
    }
}
