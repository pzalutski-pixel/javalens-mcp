package org.javalens.mcp.rewrite;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a JDT {@link TextEdit} tree (as produced by
 * {@code ASTRewrite.rewriteAST()} / {@code ImportRewrite.rewriteImports(...)})
 * into the edit-map shape JavaLens tools return. The edits are returned as
 * text for the caller to apply — nothing here writes a file.
 *
 * <p>Leaf mapping (matching the shapes the tool contracts already pin):
 * <ul>
 *   <li>{@link InsertEdit} → {@code {type: insert, line, column, offset, newText}}</li>
 *   <li>{@link ReplaceEdit} → {@code {type: replace, startLine, startColumn, endLine,
 *       endColumn, startOffset, endOffset, oldText, newText}}</li>
 *   <li>{@link DeleteEdit} → {@code {type: delete, startLine, startColumn, endLine,
 *       endColumn, startOffset, endOffset, oldText}}</li>
 * </ul>
 * All line/column values are zero-based. An unrecognized leaf type is an error:
 * silently dropping an edit would corrupt the result, so the converter throws
 * and the tool surfaces the failure.
 */
public final class TextEditConverter {

    private TextEditConverter() {
    }

    /**
     * Flatten {@code root} into edit maps, ordered by source offset.
     *
     * <p>ASTRewrite decomposes one logical change into fragmented leaves: a
     * single inserted statement arrives as several contiguous {@code InsertEdit}s
     * at the same offset, and a node replacement arrives as an insert/delete
     * pair over the same range. The fragments are coalesced here — same final
     * text, one edit map per logical change — so tool contracts stay stable.
     *
     * @param root   the edit tree from ASTRewrite/ImportRewrite
     * @param source the compilation unit's current source (for oldText extraction)
     * @param ast    the parsed AST the edits were computed against (for line/column)
     */
    public static List<Map<String, Object>> toEditMaps(TextEdit root, String source, CompilationUnit ast) {
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(root, leaves);
        leaves.sort(null); // by offset; stable, so tree order is kept within an offset

        List<Leaf> merged = new ArrayList<>();
        for (Leaf leaf : leaves) {
            Leaf last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.offset == leaf.offset && last.length == 0 && leaf.length == 0) {
                // Contiguous inserts at one offset -> one insert.
                last.text += leaf.text;
            } else if (last != null && last.offset == leaf.offset
                    && (last.length == 0) != (leaf.length == 0)) {
                // Insert + delete over the same start -> one replace.
                last.length += leaf.length;
                last.text += leaf.text;
            } else if (last != null && last.text.isEmpty() && leaf.text.isEmpty()
                    && last.offset + last.length == leaf.offset) {
                // Contiguous deletes (e.g. a removed statement and its trailing
                // whitespace arrive as separate edits) -> one delete.
                last.length += leaf.length;
            } else {
                merged.add(new Leaf(leaf.offset, leaf.length, leaf.text));
            }
        }

        List<Map<String, Object>> edits = new ArrayList<>();
        for (Leaf leaf : merged) {
            if (leaf.length == 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "insert");
                m.put("line", ast.getLineNumber(leaf.offset) - 1);
                m.put("column", ast.getColumnNumber(leaf.offset));
                m.put("offset", leaf.offset);
                m.put("newText", leaf.text);
                edits.add(m);
            } else if (leaf.text.isEmpty()) {
                edits.add(rangeEdit("delete", leaf.offset, leaf.length, null, source, ast));
            } else {
                edits.add(rangeEdit("replace", leaf.offset, leaf.length, leaf.text, source, ast));
            }
        }
        return edits;
    }

    /** A raw leaf: length==0 is an insert; text=="" with length>0 is a delete. */
    private static final class Leaf implements Comparable<Leaf> {
        int offset;
        int length;
        String text;

        Leaf(int offset, int length, String text) {
            this.offset = offset;
            this.length = length;
            this.text = text;
        }

        @Override
        public int compareTo(Leaf other) {
            return Integer.compare(offset, other.offset);
        }
    }

    private static void collectLeaves(TextEdit edit, List<Leaf> out) {
        if (edit instanceof MultiTextEdit) {
            for (TextEdit child : edit.getChildren()) {
                collectLeaves(child, out);
            }
            return;
        }
        if (edit instanceof InsertEdit insert) {
            out.add(new Leaf(insert.getOffset(), 0, insert.getText()));
            return;
        }
        if (edit instanceof ReplaceEdit replace) {
            out.add(new Leaf(replace.getOffset(), replace.getLength(), replace.getText()));
            return;
        }
        if (edit instanceof DeleteEdit delete) {
            out.add(new Leaf(delete.getOffset(), delete.getLength(), ""));
            return;
        }
        // Markers carry no textual change; move/copy containers nest real edits.
        // Reject true leaves we don't understand rather than dropping them.
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collectLeaves(child, out);
            }
            return;
        }
        if (edit.getClass().getSimpleName().equals("RangeMarker")) {
            return;
        }
        throw new IllegalStateException(
            "Unsupported TextEdit leaf: " + edit.getClass().getName() + " " + edit);
    }

    /**
     * The leaf edits of one rewrite, each given to exactly one span of the source. Used when a
     * tool's contract pins ONE edit spanning a whole node (a method signature, a call) while
     * ASTRewrite produced fine-grained edits inside it: {@link #take} renders the text a span
     * becomes, and {@link #requireAllTaken} fails when an edit was given to no span, since the
     * answer would leave it out.
     */
    public static final class Slices {

        private final List<Leaf> leaves = new ArrayList<>();
        private final boolean[] taken;

        /**
         * @param root the edit tree of the rewrite, computed against the source the spans name
         */
        public Slices(TextEdit root) {
            collectLeaves(root, leaves);
            leaves.sort(null); // by offset; stable, so tree order is kept within an offset
            this.taken = new boolean[leaves.size()];
        }

        /**
         * Where the text a span becomes goes, piece by piece, each piece naming the source text it
         * stands for, so a place in the text a span becomes can be traced to the source.
         */
        public interface Sink {

            /** The source text {@code [from, to)}, kept as it is; nothing when the range is empty. */
            void keep(int from, int to);

            /**
             * {@code text} in place of the source text {@code [from, to)}: an insertion at
             * {@code from} when the range is empty, a deletion when {@code text} is.
             */
            void replace(int from, int to, String text);
        }

        /**
         * Gives {@code sink} the text {@code [start, end)} of the source becomes when every edit
         * within it that no span has taken yet is applied, insertions at start and at end
         * included; those edits are taken. An edit crossing start or end is an error: the span's
         * text would be incomplete.
         */
        public void take(int start, int end, Sink sink) {
            take(start, end, true, sink);
        }

        /**
         * As {@link #take(int, int, Sink)}, but an insertion at {@code end} is left to the text
         * that starts there: the text before a node whose own edits begin with an insertion at
         * its start (ASTRewrite renders a replaced name as a deletion and an insertion there).
         */
        public void takeBefore(int start, int end, Sink sink) {
            take(start, end, false, sink);
        }

        private void take(int start, int end, boolean insertionsAtEnd, Sink sink) {
            int cursor = start;
            int replacedFrom = -1;
            for (int i = 0; i < leaves.size(); i++) {
                Leaf leaf = leaves.get(i);
                int leafEnd = leaf.offset + leaf.length;
                boolean outside = leaf.length == 0
                    ? leaf.offset < start || leaf.offset > end || (!insertionsAtEnd && leaf.offset == end && end > start)
                    : leafEnd <= start || leaf.offset >= end;
                if (taken[i] || outside) {
                    continue;
                }
                if (leaf.offset < start || leafEnd > end) {
                    throw new IllegalStateException("Edit crosses the span [" + start + "," + end + "): "
                        + leaf.offset + "+" + leaf.length);
                }
                if (leaf.offset >= cursor) {
                    sink.keep(cursor, leaf.offset);
                    cursor = leaf.offset;
                } else if (leaf.length > 0 || leaf.offset != replacedFrom) {
                    throw new IllegalStateException("Edits overlap at " + leaf.offset);
                }
                sink.replace(leaf.offset, leafEnd, leaf.text);
                if (leaf.length > 0) {
                    replacedFrom = leaf.offset;
                    cursor = leafEnd;
                }
                taken[i] = true;
            }
            sink.keep(cursor, end);
        }

        /** Fails when an edit of the rewrite was taken by no span. */
        public void requireAllTaken() {
            for (int i = 0; i < leaves.size(); i++) {
                if (!taken[i]) {
                    Leaf leaf = leaves.get(i);
                    throw new IllegalStateException("Edit at " + leaf.offset + "+" + leaf.length
                        + " belongs to no span: " + leaf.text);
                }
            }
        }
    }

    private static Map<String, Object> rangeEdit(String type, int offset, int length,
                                                 String newText, String source, CompilationUnit ast) {
        int end = offset + length;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("startLine", ast.getLineNumber(offset) - 1);
        m.put("startColumn", ast.getColumnNumber(offset));
        m.put("endLine", ast.getLineNumber(end) - 1);
        m.put("endColumn", ast.getColumnNumber(end));
        m.put("startOffset", offset);
        m.put("endOffset", end);
        m.put("oldText", source.substring(offset, Math.min(end, source.length())));
        if (newText != null) {
            m.put("newText", newText);
        }
        return m;
    }
}
