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
     * Renders the text a source slice becomes after applying the given edit
     * maps. Used when a tool's contract pins ONE edit spanning a whole node
     * (a method signature, a call) while ASTRewrite produced fine-grained
     * edits inside that span: the caller converts the rewrite, applies it to
     * the node's slice here, and emits a single edit with the result.
     *
     * <p>Edits fully outside the slice are ignored; an edit crossing the
     * slice boundary is an error (the aggregate would be incomplete).
     */
    public static String applyToSlice(List<Map<String, Object>> editMaps,
                                      int sliceStart, int sliceEnd, String source) {
        List<Map<String, Object>> inSlice = new ArrayList<>();
        for (Map<String, Object> edit : editMaps) {
            int start = ((Number) (edit.containsKey("offset")
                ? edit.get("offset") : edit.get("startOffset"))).intValue();
            int end = edit.containsKey("endOffset")
                ? ((Number) edit.get("endOffset")).intValue() : start;
            if (end <= sliceStart || start >= sliceEnd) {
                continue;
            }
            if (start < sliceStart || end > sliceEnd) {
                throw new IllegalStateException("Edit crosses slice boundary ["
                    + sliceStart + "," + sliceEnd + "): " + edit);
            }
            inSlice.add(edit);
        }
        // Apply back-to-front so earlier offsets stay valid.
        inSlice.sort((a, b) -> Integer.compare(
            ((Number) (b.containsKey("offset") ? b.get("offset") : b.get("startOffset"))).intValue(),
            ((Number) (a.containsKey("offset") ? a.get("offset") : a.get("startOffset"))).intValue()));

        StringBuilder out = new StringBuilder(source.substring(sliceStart, sliceEnd));
        for (Map<String, Object> edit : inSlice) {
            String type = (String) edit.get("type");
            if ("insert".equals(type)) {
                int at = ((Number) edit.get("offset")).intValue() - sliceStart;
                out.insert(at, (String) edit.get("newText"));
            } else {
                int from = ((Number) edit.get("startOffset")).intValue() - sliceStart;
                int to = ((Number) edit.get("endOffset")).intValue() - sliceStart;
                out.replace(from, to, "delete".equals(type) ? "" : (String) edit.get("newText"));
            }
        }
        return out.toString();
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
