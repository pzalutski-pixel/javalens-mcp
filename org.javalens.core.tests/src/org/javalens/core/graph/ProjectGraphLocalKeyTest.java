package org.javalens.core.graph;

import org.eclipse.jdt.core.IJavaElement;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ProjectGraph#keyOf} gives the key of a type with a canonical name and of its members,
 * and no key for a local or anonymous class, a class nested in one, or their members: none of
 * them is a graph node, and a local class named like a member class must not take its key.
 *
 * <p>Shadow.java in the unnamed-semantics fixture (package com.example.semantics; positions
 * zero-based): the member class {@code Local} (line 4, column 17) with {@code depth()} (line 5,
 * column 12); in {@code probe()}, the local class {@code Local} (line 11, column 14) with
 * {@code depth()} (line 12, column 16); in {@code runner()}, anonymous class 1 with {@code run()}
 * (line 25, column 24); in {@code nest()}, the local class
 * {@code Holder} (line 32, column 14) with the member class {@code Inner} (line 33, column 18)
 * and its {@code depth()} (line 34, column 20). Calls.java: the field {@code base} of anonymous
 * class 1 (line 34, column 22).
 */
class ProjectGraphLocalKeyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ProjectGraph graph;
    private Path shadow;
    private Path calls;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("unnamed-semantics");
        graph = service.getProjectGraphService().getGraph();
        Path sources = helper.getFixturePath("unnamed-semantics").resolve("src/main/java/com/example/semantics");
        shadow = sources.resolve("Shadow.java");
        calls = sources.resolve("Calls.java");
    }

    @Test
    @DisplayName("keyOf gives the member class Shadow.Local and its depth() their keys, which are graph nodes")
    void memberClassKeys() throws Exception {
        String type = graph.keyOf(element(shadow, 4, 17));
        String method = graph.keyOf(element(shadow, 5, 12));
        assertAll(
            () -> assertEquals("com.example.semantics.Shadow$Local", type),
            () -> assertEquals("com.example.semantics.Shadow$Local#depth()", method),
            () -> assertEquals(type, graph.node(type).key()),
            () -> assertEquals(method, graph.node(method).key()));
    }

    @Test
    @DisplayName("keyOf gives no key for the local class Local, its depth(), the local class Holder, its member class Inner and Inner's depth()")
    void localClassesHaveNoKey() throws Exception {
        assertAll(
            () -> assertNull(graph.keyOf(element(shadow, 11, 14)), "local class Local"),
            () -> assertNull(graph.keyOf(element(shadow, 12, 16)), "depth() of the local class Local"),
            () -> assertNull(graph.keyOf(element(shadow, 32, 14)), "local class Holder"),
            () -> assertNull(graph.keyOf(element(shadow, 33, 18)), "member class Inner of Holder"),
            () -> assertNull(graph.keyOf(element(shadow, 34, 20)), "depth() of Inner"));
    }

    @Test
    @DisplayName("keyOf gives no key for an anonymous class, its method run() or the field base of another")
    void anonymousClassesHaveNoKey() throws Exception {
        assertAll(
            () -> assertNull(graph.keyOf(element(shadow, 25, 24).getParent()), "anonymous class 1 of Shadow"),
            () -> assertNull(graph.keyOf(element(shadow, 25, 24)), "run() of anonymous class 1"),
            () -> assertNull(graph.keyOf(element(calls, 34, 22)), "field base of anonymous class 1 of Calls"));
    }

    private IJavaElement element(Path file, int line, int column) {
        return service.getElementAtPosition(file, line, column);
    }
}
