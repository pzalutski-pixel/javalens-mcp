package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins, at both seams, that a refactoring or clean-up call cancelled at any of its cancellation
 * checks answers exactly TIMEOUT, leaves no working copy, and leaves the next call the answer a
 * fresh load gives. Deadlines expire at a chosen cancellation check, never on the clock. The
 * fixture is described in {@link TimeLimitCalls}; introduce_parameter_object, whose calls make the
 * most checks, has a class of its own.
 */
class RefactoringTimeLimitTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final TimeLimitCalls calls = new TimeLimitCalls(helper);

    @Test
    @DisplayName("encapsulate_field: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void encapsulateField_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("encapsulate_field",
            calls.position("Circle.java", 5, 11));
        assertEquals(2, fresh.at("/data/filesAffected").asInt(), () -> "Circle.java and Canvas.java: " + fresh);
    }

    @Test
    @DisplayName("pull_up: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void pullUp_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        // JDT lists the fields width() reads in hash order, which differs between loads, so the
        // warnings are compared as a sorted list.
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("pull_up", calls.position("Ring.java", 8, 15),
            RefactoringTimeLimitTest::warningsSorted);
        assertEquals(2, fresh.at("/data/filesAffected").asInt(), () -> "Ring.java and Figure.java: " + fresh);
    }

    /** The answer with its data.warnings sorted. */
    private static JsonNode warningsSorted(JsonNode answer) {
        JsonNode copy = answer.deepCopy();
        if (copy.at("/data/warnings") instanceof com.fasterxml.jackson.databind.node.ArrayNode warnings) {
            List<String> sorted = textsOf(warnings).stream().sorted().toList();
            warnings.removeAll();
            sorted.forEach(warnings::add);
        }
        return copy;
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> texts = new java.util.ArrayList<>();
        array.forEach(element -> texts.add(element.asText()));
        return texts;
    }

    @Test
    @DisplayName("push_down: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void pushDown_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertRefactoringSweep("push_down", calls.position("Figure.java", 5, 18));
    }

    @Test
    @DisplayName("extract_superclass: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void extractSuperclass_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        ObjectNode args = calls.position("Gallery.java", 16, 18);
        args.put("superclassName", "Showroom");
        calls.assertRefactoringSweep("extract_superclass", args);
    }

    @Test
    @DisplayName("move_type_to_new_file: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void moveTypeToNewFile_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertRefactoringSweep("move_type_to_new_file", calls.position("Gallery.java", 8, 24));
    }

    @Test
    @DisplayName("apply_cleanup: cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void applyCleanup_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        ObjectNode args = calls.args("filePath", calls.file("Gallery.java"));
        args.put("cleanupId", "convert_loops");
        calls.assertRefactoringSweep("apply_cleanup", args);
    }
}
