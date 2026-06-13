package org.javalens.core.graph;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.graph.ProjectGraph.EdgeKind;
import org.javalens.core.graph.ProjectGraph.GraphEdge;
import org.javalens.core.graph.ProjectGraph.GraphNode;
import org.javalens.core.graph.ProjectGraph.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the project graph contract against the reachability-maven fixture:
 * node inventory, edge inventory (call/create/read/write ownership incl.
 * type-owned field-initializer edges), override edges, forward reachability
 * with class-hierarchy expansion, and reverse transitive-caller closure.
 */
class ProjectGraphServiceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl jdtService;
    private ProjectGraph graph;

    // Type keys
    private static final String T_MAIN = "com.reach.Main";
    private static final String T_APP = "com.reach.App";
    private static final String T_GREETER = "com.reach.Greeter";
    private static final String T_EG = "com.reach.EnglishGreeter";
    private static final String T_ORPHAN = "com.reach.Orphan";
    private static final String T_TESTED_ONLY = "com.reach.TestedOnly";
    private static final String T_BASE = "com.reach.Base";
    private static final String T_CHILD = "com.reach.Child";
    private static final String T_TOT = "com.reach.TestedOnlyTest";
    private static final String T_GDT = "com.reach.GreeterDispatchTest";
    private static final String T_WIDGET = "com.reach.Widget";
    private static final String T_WIDGET_TEST = "com.reach.WidgetTest";

    // Method keys
    private static final String MAIN = "com.reach.Main#main(String[])";
    private static final String APP_RUN = "com.reach.App#run(String)";
    private static final String APP_DEFAULT_NAME = "com.reach.App#defaultName()";
    private static final String GREETER_GREET = "com.reach.Greeter#greet(String)";
    private static final String EG_GREET = "com.reach.EnglishGreeter#greet(String)";
    private static final String EG_PREFIX = "com.reach.EnglishGreeter#prefix()";
    private static final String EG_UNUSED = "com.reach.EnglishGreeter#unusedPublicHelper()";
    private static final String ORPHAN_DEAD_METHOD = "com.reach.Orphan#deadMethod()";
    private static final String ORPHAN_DEAD_CHAIN = "com.reach.Orphan#deadChain()";
    private static final String ONLY_FROM_TEST = "com.reach.TestedOnly#onlyFromTest(int)";
    private static final String BASE_HOOK = "com.reach.Base#hook()";
    private static final String CHILD_CTOR = "com.reach.Child#Child()";
    private static final String CHILD_HOOK = "com.reach.Child#hook()";
    private static final String CHILD_CREATE = "com.reach.Child#create()";
    private static final String TOT_DOUBLES = "com.reach.TestedOnlyTest#doublesInput()";
    private static final String TOT_VIA_HELPER = "com.reach.TestedOnlyTest#viaHelper()";
    private static final String TOT_HELPER = "com.reach.TestedOnlyTest#helper()";
    private static final String GDT_GREETS = "com.reach.GreeterDispatchTest#greetsThroughInterface()";
    private static final String GDT_DISABLED = "com.reach.GreeterDispatchTest#disabledGreeting()";
    private static final String WIDGET_CTOR = "com.reach.Widget#Widget()";
    private static final String WIDGET_COMPUTE = "com.reach.Widget#compute(int)";
    private static final String WT_COMPUTES = "com.reach.WidgetTest#computesViaMember()";

    // Field keys
    private static final String F_GREETER = "com.reach.App#greeter";
    private static final String F_DEFAULT = "com.reach.App#DEFAULT";
    private static final String F_DEAD_CONSTANT = "com.reach.Orphan#DEAD_CONSTANT";
    private static final String F_WIDGET_SEED = "com.reach.Widget#seed";

    @BeforeEach
    void setUp() throws Exception {
        jdtService = helper.loadProject("reachability-maven");
        graph = jdtService.getProjectGraphService().getGraph();
    }

    // ========== Node inventory ==========

    @Test
    @DisplayName("graph contains exactly the fixture's types, methods, and fields")
    void nodeInventory() {
        assertEquals(
            Set.of(T_MAIN, T_APP, T_GREETER, T_EG, T_ORPHAN, T_TESTED_ONLY, T_BASE, T_CHILD, T_TOT, T_GDT,
                T_WIDGET, T_WIDGET_TEST),
            keys(graph.nodes(NodeKind.TYPE)));

        assertEquals(
            Set.of(MAIN, APP_RUN, APP_DEFAULT_NAME, GREETER_GREET, EG_GREET, EG_PREFIX, EG_UNUSED,
                ORPHAN_DEAD_METHOD, ORPHAN_DEAD_CHAIN, ONLY_FROM_TEST, BASE_HOOK,
                CHILD_CTOR, CHILD_HOOK, CHILD_CREATE,
                TOT_DOUBLES, TOT_VIA_HELPER, TOT_HELPER, GDT_GREETS, GDT_DISABLED,
                WIDGET_CTOR, WIDGET_COMPUTE, WT_COMPUTES),
            keys(graph.nodes(NodeKind.METHOD)));

        assertEquals(
            Set.of(F_GREETER, F_DEFAULT, F_DEAD_CONSTANT, F_WIDGET_SEED),
            keys(graph.nodes(NodeKind.FIELD)));
    }

    @Test
    @DisplayName("node metadata carries kind, simple name, flags, file, and 0-based line")
    void nodeMetadata() {
        GraphNode run = graph.node(APP_RUN);
        assertEquals(NodeKind.METHOD, run.kind());
        assertEquals("run", run.simpleName());
        assertTrue(Flags.isPublic(run.flags()), "run() is public");
        assertTrue(run.filePath().replace('\\', '/').endsWith("src/main/java/com/reach/App.java"),
            "unexpected path: " + run.filePath());
        assertEquals(14, run.line(), "0-based declaration line of run()");

        GraphNode deadChain = graph.node(ORPHAN_DEAD_CHAIN);
        assertFalse(Flags.isPublic(deadChain.flags()), "deadChain() is package-private");
        assertFalse(Flags.isPrivate(deadChain.flags()), "deadChain() is package-private");

        GraphNode deadConstant = graph.node(F_DEAD_CONSTANT);
        assertEquals(NodeKind.FIELD, deadConstant.kind());
        assertTrue(Flags.isPublic(deadConstant.flags()));
        assertTrue(Flags.isStatic(deadConstant.flags()));
    }

    // ========== Edge inventory ==========

    @Test
    @DisplayName("graph contains exactly the fixture's call/create/read edges")
    void edgeInventory() {
        Set<GraphEdge> expected = Set.of(
            // Main.main
            new GraphEdge(MAIN, T_APP, EdgeKind.CREATES),          // implicit ctor -> type node
            new GraphEdge(MAIN, APP_RUN, EdgeKind.CALLS),
            new GraphEdge(MAIN, CHILD_CREATE, EdgeKind.CALLS),
            new GraphEdge(MAIN, BASE_HOOK, EdgeKind.CALLS),
            // App field initializers (implicit ctor -> edges owned by the type)
            new GraphEdge(T_APP, T_EG, EdgeKind.CREATES),
            new GraphEdge(T_APP, APP_DEFAULT_NAME, EdgeKind.CALLS),
            // App.run
            new GraphEdge(APP_RUN, F_GREETER, EdgeKind.READS),
            new GraphEdge(APP_RUN, F_DEFAULT, EdgeKind.READS),
            new GraphEdge(APP_RUN, GREETER_GREET, EdgeKind.CALLS),
            // EnglishGreeter.greet
            new GraphEdge(EG_GREET, EG_PREFIX, EdgeKind.CALLS),
            // Orphan (unreachable island with an internal edge)
            new GraphEdge(ORPHAN_DEAD_METHOD, ORPHAN_DEAD_CHAIN, EdgeKind.CALLS),
            // Child.create (explicit ctor -> ctor node)
            new GraphEdge(CHILD_CREATE, CHILD_CTOR, EdgeKind.CREATES),
            // TestedOnlyTest
            new GraphEdge(TOT_DOUBLES, T_TESTED_ONLY, EdgeKind.CREATES),
            new GraphEdge(TOT_DOUBLES, ONLY_FROM_TEST, EdgeKind.CALLS),
            new GraphEdge(TOT_VIA_HELPER, TOT_HELPER, EdgeKind.CALLS),
            new GraphEdge(TOT_HELPER, T_TESTED_ONLY, EdgeKind.CREATES),
            new GraphEdge(TOT_HELPER, ONLY_FROM_TEST, EdgeKind.CALLS),
            // GreeterDispatchTest
            new GraphEdge(GDT_GREETS, T_EG, EdgeKind.CREATES),
            new GraphEdge(GDT_GREETS, GREETER_GREET, EdgeKind.CALLS),
            new GraphEdge(GDT_DISABLED, T_EG, EdgeKind.CREATES),
            new GraphEdge(GDT_DISABLED, GREETER_GREET, EdgeKind.CALLS),
            // WidgetTest exercises Widget only through its members (explicit
            // ctor node, method node, field read), never the type node — the
            // #32 shape across all three member kinds.
            new GraphEdge(WT_COMPUTES, WIDGET_CTOR, EdgeKind.CREATES),
            new GraphEdge(WT_COMPUTES, WIDGET_COMPUTE, EdgeKind.CALLS),
            new GraphEdge(WT_COMPUTES, F_WIDGET_SEED, EdgeKind.READS));

        assertEquals(expected, Set.copyOf(graph.edges()));
    }

    @Test
    @DisplayName("override edges link each override to its overridden declaration")
    void overrideEdges() {
        assertEquals(
            Set.of(EG_GREET + " overrides " + GREETER_GREET,
                CHILD_HOOK + " overrides " + BASE_HOOK),
            graph.overrides().entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(o -> e.getKey() + " overrides " + o))
                .collect(Collectors.toSet()));
    }

    // ========== Roots ==========

    @Test
    @DisplayName("main-method discovery finds exactly the fixture's main")
    void mainMethodKeys() {
        assertEquals(Set.of(MAIN), graph.mainMethodKeys());
    }

    // ========== Forward reachability ==========

    @Test
    @DisplayName("reachability from main: CHA reaches overrides, type-owned initializer edges fire")
    void reachableFromMain() {
        Set<String> reachable = graph.reachableFrom(Set.of(MAIN));

        assertEquals(
            Set.of(
                // methods
                MAIN, APP_RUN, APP_DEFAULT_NAME, GREETER_GREET, EG_GREET, EG_PREFIX,
                BASE_HOOK, CHILD_HOOK, CHILD_CREATE, CHILD_CTOR,
                // types (instantiated, or declaring a root)
                T_MAIN, T_APP, T_EG, T_CHILD,
                // fields (read or written by a reachable method)
                F_GREETER, F_DEFAULT),
            reachable);
    }

    @Test
    @DisplayName("reachability from main plus test roots adds the test-only chain")
    void reachableFromMainAndTests() {
        Set<String> reachable = graph.reachableFrom(
            Set.of(MAIN, TOT_DOUBLES, TOT_VIA_HELPER, GDT_GREETS));

        assertEquals(
            Set.of(
                MAIN, APP_RUN, APP_DEFAULT_NAME, GREETER_GREET, EG_GREET, EG_PREFIX,
                BASE_HOOK, CHILD_HOOK, CHILD_CREATE, CHILD_CTOR,
                TOT_DOUBLES, TOT_VIA_HELPER, TOT_HELPER, ONLY_FROM_TEST, GDT_GREETS,
                T_MAIN, T_APP, T_EG, T_CHILD, T_TESTED_ONLY, T_TOT, T_GDT,
                F_GREETER, F_DEFAULT),
            reachable);
    }

    @Test
    @DisplayName("an unreachable island stays unreachable despite its internal edge")
    void orphanIslandIsolation() {
        Set<String> reachable = graph.reachableFrom(
            Set.of(MAIN, TOT_DOUBLES, TOT_VIA_HELPER, GDT_GREETS));

        assertFalse(reachable.contains(ORPHAN_DEAD_METHOD));
        assertFalse(reachable.contains(ORPHAN_DEAD_CHAIN), "internal caller must not leak reachability");
        assertFalse(reachable.contains(EG_UNUSED));
        assertFalse(reachable.contains(F_DEAD_CONSTANT));
        assertFalse(reachable.contains(T_ORPHAN));
    }

    @Test
    @DisplayName("reachability with no roots is empty")
    void reachableFromNoRoots() {
        assertEquals(Set.of(), graph.reachableFrom(Set.of()));
    }

    // ========== Reverse closure ==========

    @Test
    @DisplayName("transitive callers walk caller chains through non-test helpers")
    void transitiveCallersOfTestedSymbol() {
        assertEquals(
            Set.of(TOT_DOUBLES, TOT_HELPER, TOT_VIA_HELPER),
            graph.transitiveCallers(ONLY_FROM_TEST));
    }

    // ========== Type-aware reverse closure (symbol-level, #32) ==========

    @Test
    @DisplayName("transitiveCallers on a type node alone misses member-only coverage")
    void transitiveCallers_typeNode_missesMembers() {
        // The raw type-node closure is what shipped in 1.4.2: Widget is
        // exercised only through its ctor and method nodes, so the type node
        // has no incoming edges and this returns empty - the #32 bug.
        assertEquals(Set.of(), graph.transitiveCallers(T_WIDGET));
    }

    @Test
    @DisplayName("transitiveCallersOfSymbol on a type aggregates its members' callers")
    void transitiveCallersOfSymbol_typeAggregatesMembers() {
        assertEquals(Set.of(WT_COMPUTES), graph.transitiveCallersOfSymbol(T_WIDGET));
    }

    @Test
    @DisplayName("transitiveCallersOfSymbol on a method matches transitiveCallers")
    void transitiveCallersOfSymbol_methodUnchanged() {
        assertEquals(
            graph.transitiveCallers(ONLY_FROM_TEST),
            graph.transitiveCallersOfSymbol(ONLY_FROM_TEST));
    }

    @Test
    @DisplayName("transitiveCallersOfSymbol on a type unions the type node's own callers with members'")
    void transitiveCallersOfSymbol_typeIncludesTypeNodeCallers() {
        // EnglishGreeter is created directly (type node, implicit ctor) by App
        // and both dispatch tests, AND its greet/prefix members are covered.
        // The symbol-level closure must include both the type-node creators
        // and the member-level callers.
        Set<String> callers = graph.transitiveCallersOfSymbol(T_EG);
        assertTrue(callers.contains(GDT_GREETS), "direct creator via type node");
        assertTrue(callers.contains(GDT_DISABLED), "direct creator via type node");
        assertTrue(callers.contains(APP_RUN), "caller of a member (greet/prefix)");
        assertTrue(callers.contains(MAIN), "transitive caller through App.run");
    }

    @Test
    @DisplayName("transitive callers climb override declarations: callers via the interface count")
    void transitiveCallersThroughOverride() {
        assertEquals(
            Set.of(EG_GREET, APP_RUN, MAIN, GDT_GREETS, GDT_DISABLED),
            graph.transitiveCallers(EG_PREFIX));
    }

    @Test
    @DisplayName("override-only method is reached in reverse from callers of the overridden declaration")
    void transitiveCallersOfOverrideOnlyMethod() {
        assertEquals(Set.of(MAIN), graph.transitiveCallers(CHILD_HOOK));
    }

    @Test
    @DisplayName("type-owned initializer edges resolve to creators of the type in reverse")
    void transitiveCallersThroughTypeInitializer() {
        assertEquals(Set.of(MAIN), graph.transitiveCallers(APP_DEFAULT_NAME));
    }

    @Test
    @DisplayName("reverse closure is root-independent: unreachable callers are still reported")
    void transitiveCallersInsideUnreachableIsland() {
        assertEquals(Set.of(ORPHAN_DEAD_METHOD), graph.transitiveCallers(ORPHAN_DEAD_CHAIN));
    }

    @Test
    @DisplayName("a method nobody calls has no transitive callers")
    void transitiveCallersOfEntryPoint() {
        assertEquals(Set.of(), graph.transitiveCallers(MAIN));
    }

    // ========== Element-to-key resolution ==========

    @Test
    @DisplayName("keyOf maps IType, IMethod, and IField to graph keys")
    void keyOfResolvesElements() throws Exception {
        IType testedOnly = jdtService.findType("com.reach.TestedOnly");
        assertEquals(T_TESTED_ONLY, graph.keyOf(testedOnly));

        IMethod method = testedOnly.getMethods()[0];
        assertEquals(ONLY_FROM_TEST, graph.keyOf(method));

        IType orphan = jdtService.findType("com.reach.Orphan");
        IField field = orphan.getField("DEAD_CONSTANT");
        assertEquals(F_DEAD_CONSTANT, graph.keyOf(field));
    }

    // ========== Lifecycle ==========

    @Test
    @DisplayName("graph is built once and rebuilt only after invalidate")
    void graphIsCachedUntilInvalidated() throws Exception {
        ProjectGraphService service = jdtService.getProjectGraphService();
        ProjectGraph first = service.getGraph();
        assertSame(first, service.getGraph(), "second query must reuse the built graph");

        service.invalidate();
        ProjectGraph rebuilt = service.getGraph();
        assertNotSame(first, rebuilt, "invalidate must force a rebuild");
        assertEquals(keys(first.nodes(NodeKind.METHOD)), keys(rebuilt.nodes(NodeKind.METHOD)));
    }

    private static Set<String> keys(java.util.Collection<GraphNode> nodes) {
        return nodes.stream().map(GraphNode::key).collect(Collectors.toSet());
    }
}
