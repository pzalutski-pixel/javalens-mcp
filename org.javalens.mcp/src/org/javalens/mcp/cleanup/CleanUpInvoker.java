package org.javalens.mcp.cleanup;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.fix.BooleanValueRatherThanComparisonFixCore;
import org.eclipse.jdt.internal.corext.fix.ConvertLoopFixCore;
import org.eclipse.jdt.internal.corext.fix.DoWhileRatherThanWhileFixCore;
import org.eclipse.jdt.internal.corext.fix.ElseIfFixCore;
import org.eclipse.jdt.internal.corext.fix.InvertEqualsFixCore;
import org.eclipse.jdt.internal.corext.fix.LambdaExpressionsFixCore;
import org.eclipse.jdt.internal.corext.fix.OverriddenAssignmentFixCore;
import org.eclipse.jdt.internal.corext.fix.PatternMatchingForInstanceofFixCore;
import org.eclipse.jdt.internal.corext.fix.StringConcatToTextBlockFixCore;
import org.eclipse.jdt.internal.corext.fix.SwitchExpressionsFixCore;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Invokes JDT's own clean-up operations (from org.eclipse.jdt.core.manipulation)
 * headlessly and returns the resulting edits as text. JavaLens never writes the
 * file — the caller gets the rewritten source and applies it.
 *
 * <p>The clean-up operations live in the IDE-independent core.manipulation bundle,
 * but they read a handful of preferences that the desktop IDE normally seeds (the
 * import-rewrite order/thresholds). {@link #ensureHeadlessEnvironment()} sets the
 * manipulation preference node and those defaults so the operations run without
 * the Eclipse UI.
 */
public final class CleanUpInvoker {

    /** A catalog entry: the whole-file fix factory plus the AI-facing description. */
    private record CleanUpDef(String description, Function<CompilationUnit, ICleanUpFix> factory) {
    }

    /** Clean-up id -> definition. Iteration order is the documentation order. */
    private static final Map<String, CleanUpDef> CLEAN_UPS = new LinkedHashMap<>();
    static {
        CLEAN_UPS.put("convert_loops", new CleanUpDef(
            "rewrite index- and iterator-based for loops as enhanced for loops",
            ast -> ConvertLoopFixCore.createCleanUp(ast, true, true, false, false)));
        CLEAN_UPS.put("convert_to_lambda", new CleanUpDef(
            "convert anonymous classes implementing a functional interface to lambdas",
            ast -> LambdaExpressionsFixCore.createCleanUp(ast, true, false, false)));
        CLEAN_UPS.put("pattern_matching_instanceof", new CleanUpDef(
            "use pattern matching for instanceof checks followed by a cast",
            PatternMatchingForInstanceofFixCore::createCleanUp));
        CLEAN_UPS.put("convert_to_switch_expression", new CleanUpDef(
            "convert assignment/return switch statements to switch expressions",
            SwitchExpressionsFixCore::createCleanUp));
        CLEAN_UPS.put("string_concat_to_text_block", new CleanUpDef(
            "convert multi-line string concatenations to text blocks",
            ast -> StringConcatToTextBlockFixCore.createCleanUp(ast, false)));
        CLEAN_UPS.put("do_while_rather_than_while", new CleanUpDef(
            "replace while loops that always run once with do-while loops",
            DoWhileRatherThanWhileFixCore::createCleanUp));
        CLEAN_UPS.put("invert_equals", new CleanUpDef(
            "invert equals() calls so the constant is the receiver (avoids NPEs)",
            InvertEqualsFixCore::createCleanUp));
        CLEAN_UPS.put("boolean_value_rather_than_comparison", new CleanUpDef(
            "simplify comparisons with boolean literals (x == true -> x)",
            BooleanValueRatherThanComparisonFixCore::createCleanUp));
        CLEAN_UPS.put("else_if", new CleanUpDef(
            "collapse else blocks containing a lone if into else-if chains",
            ElseIfFixCore::createCleanUp));
        CLEAN_UPS.put("overridden_assignment", new CleanUpDef(
            "remove initializers that are overwritten before being read",
            ast -> OverriddenAssignmentFixCore.createCleanUp(ast, true)));
    }

    private CleanUpInvoker() {
    }

    /** Clean-up id -> AI-facing description, in documentation order. */
    public static Map<String, String> catalog() {
        Map<String, String> catalog = new LinkedHashMap<>();
        CLEAN_UPS.forEach((id, def) -> catalog.put(id, def.description()));
        return catalog;
    }

    /** The clean-up ids this tool can apply. */
    public static java.util.Set<String> supportedCleanUps() {
        return CLEAN_UPS.keySet();
    }

    /**
     * Apply a named clean-up to the whole compilation unit.
     *
     * @return the result; {@link CleanUpResult#changed()} is false when the
     *         clean-up found nothing to do (the source is returned unchanged).
     * @throws IllegalArgumentException if the id is unknown
     */
    public static CleanUpResult apply(ICompilationUnit cu, String cleanUpId) throws Exception {
        CleanUpDef def = CLEAN_UPS.get(cleanUpId);
        if (def == null) {
            throw new IllegalArgumentException("Unknown cleanUpId: " + cleanUpId
                + ". Supported: " + supportedCleanUps());
        }

        org.javalens.mcp.rewrite.HeadlessJdtEnvironment.ensure();

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        String original = cu.getSource();

        ICleanUpFix fix = def.factory().apply(ast);
        if (fix == null) {
            return new CleanUpResult(false, original, null);
        }

        // getPreviewContent applies the change's edits to the unit's current
        // content and returns the rewritten source as a String — so we never
        // touch the file or the UI-only jface text document types.
        CompilationUnitChange change = fix.createChange(new NullProgressMonitor());
        String preview = change.getPreviewContent(new NullProgressMonitor());
        boolean changed = !preview.equals(original);
        return new CleanUpResult(changed, changed ? preview : original, changed ? change.getName() : null);
    }

    /** The outcome of a clean-up: whether anything changed, the resulting source, and a label. */
    public record CleanUpResult(boolean changed, String source, String label) {
    }
}
