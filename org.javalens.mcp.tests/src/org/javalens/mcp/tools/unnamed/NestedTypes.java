package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.javalens.core.JdtServiceImpl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The nested-types fixture and the helpers its tests share.
 *
 * <p>Package com.example.util declares {@code Tools} with the static method
 * {@code trim(String)}. Package com.example.nested declares: {@code Shelf}, with a public
 * constructor and the public static member class {@code Folio}; {@code Graph}, which imports
 * the member type {@code java.util.Map.Entry}, the package {@code com.example.util} on demand
 * and the static method {@code Tools.trim}, extends Shelf, implements Runnable and declares the
 * fields {@code Entry<String, Integer> entry}, {@code Tools tools} and {@code Shelf.Folio folio};
 * {@code Index}, which imports the members of the type {@code java.util.Map} on demand and the
 * static members of {@code Tools} on demand; {@code Outer}, which declares the inner class
 * {@code Inner} (method {@code descend()}), the static member class {@code Nested} (method
 * {@code settle()}), the member record {@code Point} (method {@code sum()}) and, in
 * {@code task()}, the local class {@code Step} (method {@code advance()}); {@code Base}, with
 * the static field {@code instances}, a constructor, the static method {@code reset()} and the
 * instance method {@code hook()}, extended by {@code Derived}; {@code Root}, with the instance
 * method {@code grow()}, extended by {@code Leaf}; {@code Owner}, whose {@code first()} returns
 * an anonymous Runnable (anonymous class 1), {@code second()} an anonymous Base subclass with the
 * method {@code tune()} (anonymous class 2), and {@code third()} declares the local class
 * {@code Twig} extending Root; and {@code Flows}, holding the expressions and variables whose
 * types are captures or anonymous classes. The default package declares {@code Ledger}, with a
 * field of the default-package type {@code Page}. Every file compiles. Positions are zero-based.
 */
final class NestedTypes {

    static final String FIXTURE = "nested-types";
    static final String NESTED = "src/main/java/com/example/nested/";
    static final String FLOWS = NESTED + "Flows.java";
    static final String OUTER = NESTED + "Outer.java";

    private NestedTypes() {
    }

    /** {@code source} with {@code edits} applied, the last in the source first. */
    static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(NestedTypes::start).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            String newText = (String) edit.getOrDefault("newText", "");
            if ("insert".equals(edit.get("type"))) {
                text.insert(start(edit), newText);
            } else {
                text.replace(start(edit), ((Number) edit.get("endOffset")).intValue(), newText);
            }
        }
        return text.toString();
    }

    private static int start(Map<String, Object> edit) {
        return ((Number) edit.getOrDefault("offset", edit.get("startOffset"))).intValue();
    }

    /** The messages of the compile errors in {@code file}, as {@code service} resolves it. */
    static List<String> errors(JdtServiceImpl service, Path file) {
        ICompilationUnit unit = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).filter(IProblem::isError).map(IProblem::getMessage).toList();
    }

    /**
     * {@code payload} without the raw offsets of the edits in the array at {@code path} (field
     * names from the root), which depend on the checkout's line endings, and with each line
     * ending of their text as "\n"; line and column pin each edit.
     */
    static UnaryOperator<JsonNode> withoutOffsets(String... path) {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            JsonNode edits = copy;
            for (String name : path) {
                edits = edits.path(name);
            }
            edits.forEach(NestedTypes::normalizeEdit);
            return copy;
        };
    }

    /**
     * {@code payload} without the raw offsets of the edits in data.editsByFile, which depend on
     * the checkout's line endings, and with each line ending of those edits' texts and of
     * data.createdFiles' contents as "\n".
     */
    static UnaryOperator<JsonNode> unixFiles() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            copy.path("data").path("editsByFile").forEach(edits -> edits.forEach(NestedTypes::normalizeEdit));
            copy.path("data").path("createdFiles").forEach(file -> {
                if (file instanceof ObjectNode object && object.get("content") instanceof TextNode content) {
                    object.put("content", content.asText().replace("\r\n", "\n"));
                }
            });
            return copy;
        };
    }

    /** {@code edit} without its raw offsets, and its texts with "\n" line endings. */
    static void normalizeEdit(JsonNode edit) {
        if (edit instanceof ObjectNode object) {
            object.remove(List.of("offset", "startOffset", "endOffset"));
            for (String field : List.of("newText", "oldText")) {
                if (object.get(field) instanceof TextNode text) {
                    object.put(field, text.asText().replace("\r\n", "\n"));
                }
            }
        }
    }
}
