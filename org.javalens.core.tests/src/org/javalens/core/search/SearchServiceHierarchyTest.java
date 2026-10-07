package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SearchService.hierarchyOf answers with the Java model's own elements, under the model's
 * numbering of anonymous classes, for types declared in and after lambda bodies. Expected lists are
 * derived from the lambda-hierarchy fixture source (package com.example.hierarchy):
 *
 * <ul>
 *   <li>Chain.java: Chain; interfaces Sink&lt;T&gt; (put(T)) and Pipe&lt;T&gt; extends Sink&lt;T&gt;;
 *       classes Base implements Sink&lt;String&gt; (put(String), static count(), weight()),
 *       Middle extends Base implements Pipe&lt;String&gt; (take(), put(String, int)), Leaf extends
 *       Middle (put(String), static count(), weight()), Ranked implements Comparable&lt;Base&gt;
 *       (compareTo(Base)).</li>
 *   <li>Host.java: Host; interface Mark (tag()); class Plate (size()); in marks() anonymous
 *       classes 1 (a Mark), 2 (a Mark, in a lambda body), 3 (a Mark), 4 (an Object), 5 (a Mark);
 *       in plates() the local class Thin extends Plate in a lambda body, then the local class
 *       Thick extends Plate and anonymous class 6, a Plate; each overrides tag or size.</li>
 *   <li>Locals.java: Locals; enum Mode (shout()) whose constant LOUD has a body overriding shout
 *       (Mode's anonymous class 1); annotation type Flag; class Named extends Chain.Base
 *       (put(String)); in sinks() a lambda implementing Sink, the local class Local extends
 *       Chain.Base (put(String)), the local class Deeper extends Local, anonymous class 1, a Local
 *       created in a lambda body (put(String)), anonymous class 2, a Chain.Sink&lt;String&gt;
 *       (put(String)); record Pair; in pairs() the local record Twin.</li>
 *   <li>Ledger.java: the top-level record Ledger, alone in its file. The search index records a
 *       record's superclass as Object (SourceIndexerRequestor.enterRecord through enterClass with
 *       no superclass, SuperTypeReferencePattern.createIndexKey :56-57), so no index entry leads
 *       JDT's hierarchy of java.lang.Record to this file.</li>
 *   <li>Twins.java: Twins; in first() and in second() a local class Echo.</li>
 *   <li>Yard.java: Yard; classes Brick (heft()), Beam (load()) and Slab (depth()); anonymous class
 *       1, a Brick created in a lambda body; in beams() the local classes Joist and Rafter extend
 *       Beam; in slabs() the local class Wet extends Slab in a lambda body, then the local class Dry
 *       extends Slab; class Post and, in posts(), the local class Pillar extending it.</li>
 *   <li>Mosaic.java: Mosaic; class Tile (span(), grout()); in pieces() two local classes Echo in
 *       sibling blocks, the first extending Tile (glaze()), the second extending nothing.</li>
 *   <li>Weave.java: Weave; interface Knot (tie()); class Loop (tie()), which does not implement
 *       Knot; class Hitch extends Loop implements Knot (tie()); interface Bend (join(String));
 *       class Splice (join(String)); class Sheet extends Splice implements Bend, declaring no
 *       method, so that Splice's join implements Bend's in Sheet; interface Hook (hang(String));
 *       class Peg&lt;T&gt; (hang(T)); class Rack extends Peg&lt;String&gt; implements Hook,
 *       declaring no method; static methods fasten and link calling tie and join.</li>
 * </ul>
 *
 * <p>Lists that hold types run by file, then by where each declaration starts, binary types last;
 * superclasses from the nearest; interfaces in declaration order, each followed by its own
 * superinterfaces, then those of the superclass. For the implicit roots (Object, Enum, Record,
 * Annotation) only the source types are pinned: the JDK's own subtypes come from the JDK running
 * the tests and follow them.
 */
class SearchServiceHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String P = "com.example.hierarchy.";

    private JdtServiceImpl service;
    private SearchService search;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("lambda-hierarchy");
        search = service.getSearchService();
    }

    @Test
    @DisplayName("Mark: no supertypes; subtypes anonymous classes 1, 2 (in a lambda body), 3 and 5; no subclasses, being an interface")
    void interfaceWithAnonymousImplementors() throws CoreException {
        ModelHierarchy mark = search.hierarchyOf(type("Host.Mark"));
        List<String> implementors = names(P + "Host$1", P + "Host$2", P + "Host$3", P + "Host$5");
        assertAll(
            () -> assertNull(mark.superclass()),
            () -> assertEquals(List.of(), typeNames(mark.superInterfaces())),
            () -> assertEquals(List.of(), typeNames(mark.allSuperclasses())),
            () -> assertEquals(List.of(), typeNames(mark.allSuperInterfaces())),
            () -> assertEquals(List.of(), typeNames(mark.allSupertypes())),
            () -> assertEquals(List.of(), typeNames(mark.subclasses())),
            () -> assertEquals(implementors, typeNames(mark.subtypes())),
            () -> assertEquals(implementors, typeNames(mark.allSubtypes())));
    }

    @Test
    @DisplayName("Plate: superclass Object; subclasses Thin (in a lambda body), Thick and anonymous class 6")
    void classWithLocalSubclasses() throws CoreException {
        ModelHierarchy plate = search.hierarchyOf(type("Host.Plate"));
        List<String> subclasses = names(P + "Host$Thin", P + "Host$Thick", P + "Host$6");
        assertAll(
            () -> assertEquals("java.lang.Object", plate.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of("java.lang.Object"), typeNames(plate.allSuperclasses())),
            () -> assertEquals(List.of(), typeNames(plate.allSuperInterfaces())),
            () -> assertEquals(subclasses, typeNames(plate.subclasses())),
            () -> assertEquals(subclasses, typeNames(plate.subtypes())),
            () -> assertEquals(subclasses, typeNames(plate.allSubtypes())));
    }

    @Test
    @DisplayName("Anonymous class 2, in a lambda body: superclass Object, interface Mark, supertypes Mark then Object, no subtypes")
    void anonymousClassInLambda() throws CoreException {
        ModelHierarchy second = search.hierarchyOf(type("Host$2"));
        assertAll(
            () -> assertEquals("java.lang.Object", second.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(P + "Host$Mark"), typeNames(second.superInterfaces())),
            () -> assertEquals(List.of("java.lang.Object"), typeNames(second.allSuperclasses())),
            () -> assertEquals(List.of(P + "Host$Mark"), typeNames(second.allSuperInterfaces())),
            () -> assertEquals(List.of(P + "Host$Mark", "java.lang.Object"), typeNames(second.allSupertypes())),
            () -> assertEquals(List.of(), typeNames(second.subclasses())),
            () -> assertEquals(List.of(), typeNames(second.subtypes())),
            () -> assertEquals(List.of(), typeNames(second.allSubtypes())));
    }

    @Test
    @DisplayName("Thin, a local class in a lambda body: superclasses Plate then Object")
    void localClassInLambda() throws CoreException {
        ModelHierarchy thin = search.hierarchyOf(type("Host$Thin"));
        assertAll(
            () -> assertEquals(P + "Host$Plate", thin.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(P + "Host$Plate", "java.lang.Object"), typeNames(thin.allSuperclasses())),
            () -> assertEquals(List.of(P + "Host$Plate", "java.lang.Object"), typeNames(thin.allSupertypes())),
            () -> assertEquals(List.of(), typeNames(thin.allSubtypes())));
    }

    @Test
    @DisplayName("Leaf: superclasses Middle, Base, Object; interfaces Pipe then Sink; supertypes Middle, Pipe, Sink, Base, Object")
    void grandparentChain() throws CoreException {
        ModelHierarchy leaf = search.hierarchyOf(type("Chain.Leaf"));
        assertAll(
            () -> assertEquals(P + "Chain$Middle", leaf.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(), typeNames(leaf.superInterfaces())),
            () -> assertEquals(List.of(P + "Chain$Middle", P + "Chain$Base", "java.lang.Object"),
                typeNames(leaf.allSuperclasses())),
            () -> assertEquals(List.of(P + "Chain$Pipe", P + "Chain$Sink"), typeNames(leaf.allSuperInterfaces())),
            () -> assertEquals(List.of(P + "Chain$Middle", P + "Chain$Pipe", P + "Chain$Sink", P + "Chain$Base",
                "java.lang.Object"), typeNames(leaf.allSupertypes())),
            () -> assertEquals(List.of(), typeNames(leaf.allSubtypes())));
    }

    @Test
    @DisplayName("Sink<T>: direct subtypes Pipe, Base and anonymous class 2 of Locals; all subtypes across files; never Ranked or the lambda quiet")
    void genericInterface() throws CoreException {
        ModelHierarchy sink = search.hierarchyOf(type("Chain.Sink"));
        assertAll(
            () -> assertNull(sink.superclass()),
            () -> assertEquals(List.of(), typeNames(sink.subclasses())),
            () -> assertEquals(names(P + "Chain$Pipe", P + "Chain$Base", P + "Locals$2"), typeNames(sink.subtypes())),
            () -> assertEquals(names(P + "Chain$Pipe", P + "Chain$Base", P + "Chain$Middle", P + "Chain$Leaf",
                P + "Locals$Named", P + "Locals$Local", P + "Locals$Deeper", P + "Locals$1", P + "Locals$2"),
                typeNames(sink.allSubtypes())));
    }

    @Test
    @DisplayName("Base: superclass Object, interface Sink; subclasses Middle, Named and Local; all subtypes add Leaf, Deeper and anonymous class 1 of Locals")
    void classAcrossFiles() throws CoreException {
        ModelHierarchy base = search.hierarchyOf(type("Chain.Base"));
        List<String> direct = names(P + "Chain$Middle", P + "Locals$Named", P + "Locals$Local");
        assertAll(
            () -> assertEquals("java.lang.Object", base.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(P + "Chain$Sink"), typeNames(base.superInterfaces())),
            () -> assertEquals(direct, typeNames(base.subclasses())),
            () -> assertEquals(direct, typeNames(base.subtypes())),
            () -> assertEquals(names(P + "Chain$Middle", P + "Chain$Leaf", P + "Locals$Named", P + "Locals$Local",
                P + "Locals$Deeper", P + "Locals$1"), typeNames(base.allSubtypes())));
    }

    @Test
    @DisplayName("Local, a local class: superclass Base, supertypes Base, Sink, Object; subclasses Deeper and anonymous class 1, a Local created in a lambda body")
    void localClassWithLocalAndAnonymousSubclasses() throws CoreException {
        ModelHierarchy local = search.hierarchyOf(type("Locals$Local"));
        List<String> subclasses = names(P + "Locals$Deeper", P + "Locals$1");
        assertAll(
            () -> assertEquals(P + "Chain$Base", local.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(), typeNames(local.superInterfaces())),
            () -> assertEquals(List.of(P + "Chain$Base", "java.lang.Object"), typeNames(local.allSuperclasses())),
            () -> assertEquals(List.of(P + "Chain$Sink"), typeNames(local.allSuperInterfaces())),
            () -> assertEquals(List.of(P + "Chain$Base", P + "Chain$Sink", "java.lang.Object"),
                typeNames(local.allSupertypes())),
            () -> assertEquals(subclasses, typeNames(local.subclasses())),
            () -> assertEquals(subclasses, typeNames(local.subtypes())),
            () -> assertEquals(subclasses, typeNames(local.allSubtypes())));
    }

    @Test
    @DisplayName("Anonymous class 1 of Locals, a Local in a lambda body: superclasses Local, Base, Object")
    void anonymousSubclassOfLocalClass() throws CoreException {
        ModelHierarchy first = search.hierarchyOf(type("Locals$1"));
        assertAll(
            () -> assertEquals(P + "Locals$Local", first.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(P + "Locals$Local", P + "Chain$Base", "java.lang.Object"),
                typeNames(first.allSuperclasses())),
            () -> assertEquals(List.of(), typeNames(first.allSubtypes())));
    }

    @Test
    @DisplayName("Mode: superclass Enum; its one subtype is the body of LOUD, whose superclasses are Mode, Enum, Object")
    void enumConstantBody() throws CoreException {
        ModelHierarchy mode = search.hierarchyOf(type("Locals.Mode"));
        ModelHierarchy loud = search.hierarchyOf(type("Locals$Mode$1"));
        assertAll(
            () -> assertEquals("java.lang.Enum", mode.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of(P + "Locals$Mode$1"), typeNames(mode.subclasses())),
            () -> assertEquals(List.of(P + "Locals$Mode$1"), typeNames(mode.subtypes())),
            () -> assertEquals(List.of(P + "Locals$Mode$1"), typeNames(mode.allSubtypes())),
            () -> assertEquals(List.of(P + "Locals$Mode", "java.lang.Enum", "java.lang.Object"),
                typeNames(loud.allSuperclasses())));
    }

    @Test
    @DisplayName("Ranked implements Comparable; Pair and Twin extend Record; Flag implements Annotation")
    void implicitAndBinarySupertypes() throws CoreException {
        assertAll(
            () -> assertEquals(List.of("java.lang.Comparable"), typeNames(search.hierarchyOf(type("Chain.Ranked")).superInterfaces())),
            () -> assertEquals("java.lang.Record", search.hierarchyOf(type("Locals.Pair")).superclass().getFullyQualifiedName()),
            () -> assertEquals("java.lang.Record", search.hierarchyOf(type("Locals$Twin")).superclass().getFullyQualifiedName()),
            () -> assertNull(search.hierarchyOf(type("Locals.Flag")).superclass()),
            () -> assertEquals(List.of("java.lang.annotation.Annotation"),
                typeNames(search.hierarchyOf(type("Locals.Flag")).superInterfaces())));
    }

    @Test
    @DisplayName("Object's direct source subtypes: every class declared without a superclass, anonymous classes of interfaces and of Object, local classes in both Twins methods")
    void objectDirectSubtypes() throws CoreException {
        ModelHierarchy object = search.hierarchyOf(service.findType("java.lang.Object"));
        List<String> expected = names(
            P + "Chain", P + "Chain$Base", P + "Chain$Ranked",
            P + "Host", P + "Host$Plate", P + "Host$1", P + "Host$2", P + "Host$3", P + "Host$4", P + "Host$5",
            P + "Locals", P + "Locals$2",
            P + "Mosaic", P + "Mosaic$Tile", P + "Mosaic$Echo",
            P + "Twins", P + "Twins$Echo", P + "Twins$Echo",
            P + "Weave", P + "Weave$Loop", P + "Weave$Splice", P + "Weave$Peg",
            P + "Yard", P + "Yard$Brick", P + "Yard$Beam", P + "Yard$Slab", P + "Yard$Post");
        assertAll(
            () -> assertEquals(expected, sourcePrefix(object.subtypes())),
            () -> assertEquals(expected, sourcePrefix(object.subclasses())));
    }

    @Test
    @DisplayName("Object's source subtypes: every class, enums, records and their local and anonymous kin included; no interface or annotation type")
    void objectAllSubtypes() throws CoreException {
        ModelHierarchy object = search.hierarchyOf(service.findType("java.lang.Object"));
        assertEquals(names(
            P + "Chain", P + "Chain$Base", P + "Chain$Middle", P + "Chain$Leaf", P + "Chain$Ranked",
            P + "Host", P + "Host$Plate", P + "Host$1", P + "Host$2", P + "Host$3", P + "Host$4", P + "Host$5",
            P + "Host$Thin", P + "Host$Thick", P + "Host$6",
            P + "Ledger",
            P + "Locals", P + "Locals$Mode", P + "Locals$Mode$1", P + "Locals$Named", P + "Locals$Local",
            P + "Locals$Deeper", P + "Locals$1", P + "Locals$2", P + "Locals$Pair", P + "Locals$Twin",
            P + "Mosaic", P + "Mosaic$Tile", P + "Mosaic$Echo", P + "Mosaic$Echo",
            P + "Twins", P + "Twins$Echo", P + "Twins$Echo",
            P + "Weave", P + "Weave$Loop", P + "Weave$Hitch", P + "Weave$Splice", P + "Weave$Sheet", P + "Weave$Peg",
            P + "Weave$Rack",
            P + "Yard", P + "Yard$Brick", P + "Yard$1", P + "Yard$Beam", P + "Yard$Joist", P + "Yard$Rafter",
            P + "Yard$Slab", P + "Yard$Wet", P + "Yard$Dry", P + "Yard$Post", P + "Yard$Pillar"),
            sourcePrefix(object.allSubtypes()));
    }

    @Test
    @DisplayName("Enum's source subtypes are Mode, then also the body of LOUD; Annotation's is Flag")
    void otherImplicitRoots() throws CoreException {
        ModelHierarchy enumRoot = search.hierarchyOf(service.findType("java.lang.Enum"));
        ModelHierarchy annotation = search.hierarchyOf(service.findType("java.lang.annotation.Annotation"));
        assertAll(
            () -> assertEquals(List.of(P + "Locals$Mode"), sourcePrefix(enumRoot.subtypes())),
            () -> assertEquals(List.of(P + "Locals$Mode", P + "Locals$Mode$1"), sourcePrefix(enumRoot.allSubtypes())),
            () -> assertEquals(List.of(P + "Locals$Flag"), sourcePrefix(annotation.subtypes())),
            () -> assertEquals(List.of(P + "Locals$Flag"), sourcePrefix(annotation.allSubtypes())));
    }

    @Test
    @DisplayName("Record's source subtypes are every record: Ledger, which no index entry leads to, the member Pair and the local Twin")
    void recordRootListsEveryRecord() throws CoreException {
        ModelHierarchy record = search.hierarchyOf(service.findType("java.lang.Record"));
        List<String> records = List.of(P + "Ledger", P + "Locals$Pair", P + "Locals$Twin");
        assertAll(
            () -> assertEquals(records, sourcePrefix(record.subtypes())),
            () -> assertEquals(records, sourcePrefix(record.allSubtypes())));
    }

    @Test
    @DisplayName("overriding: Mark.tag in anonymous classes 1, 2, 3, 5; Plate.size in Thin, Thick, anonymous class 6; Mode.shout in LOUD's body")
    void overridingInLocalAndAnonymousClasses() throws CoreException {
        assertAll(
            () -> assertEquals(List.of(P + "Host$1#tag()", P + "Host$2#tag()", P + "Host$3#tag()", P + "Host$5#tag()"),
                methodNames(search.hierarchyOf(type("Host.Mark")).overriding(method("Host.Mark", "tag", 0)))),
            () -> assertEquals(List.of(P + "Host$Thin#size()", P + "Host$Thick#size()", P + "Host$6#size()"),
                methodNames(search.hierarchyOf(type("Host.Plate")).overriding(method("Host.Plate", "size", 0)))),
            () -> assertEquals(List.of(P + "Locals$Mode$1#shout()"),
                methodNames(search.hierarchyOf(type("Locals.Mode")).overriding(method("Locals.Mode", "shout", 0)))));
    }

    @Test
    @DisplayName("overriding: Sink.put(T) by put(String) in six classes, never Middle's put(String, int); Base.put(String) in four; Base.weight in Leaf; the static Base.count in none")
    void overridingWithTypeArgumentsOverloadsAndStatics() throws CoreException {
        ModelHierarchy base = search.hierarchyOf(type("Chain.Base"));
        assertAll(
            () -> assertEquals(List.of(P + "Chain$Base#put(String)", P + "Chain$Leaf#put(String)",
                    P + "Locals$Named#put(String)", P + "Locals$Local#put(String)", P + "Locals$1#put(String)",
                    P + "Locals$2#put(String)"),
                methodNames(search.hierarchyOf(type("Chain.Sink")).overriding(method("Chain.Sink", "put", 1)))),
            () -> assertEquals(List.of(P + "Chain$Leaf#put(String)", P + "Locals$Named#put(String)",
                    P + "Locals$Local#put(String)", P + "Locals$1#put(String)"),
                methodNames(base.overriding(method("Chain.Base", "put", 1)))),
            () -> assertEquals(List.of(P + "Chain$Leaf#weight()"), methodNames(base.overriding(method("Chain.Base", "weight", 0)))),
            () -> assertEquals(List.of(), methodNames(base.overriding(method("Chain.Base", "count", 0)))));
    }

    @Test
    @DisplayName("overriding Comparable.compareTo: the source override is Ranked's compareTo(Base)")
    void overridingBinaryMethod() throws CoreException {
        IType comparable = service.findType("java.lang.Comparable");
        IMethod compareTo = Arrays.stream(comparable.getMethods())
            .filter(m -> m.getElementName().equals("compareTo")).findFirst().orElseThrow();
        List<String> source = new ArrayList<>();
        for (IMethod overrider : search.hierarchyOf(comparable).overriding(compareTo)) {
            if (!overrider.isBinary()) {
                source.add(methodName(overrider));
            }
        }
        assertEquals(List.of(P + "Chain$Ranked#compareTo(Base)"), source);
    }

    @Test
    @DisplayName("overriding: Leaf.weight, of a class without subtypes, has no overrider")
    void overridingWithoutSubtypes() throws CoreException {
        assertEquals(List.of(),
            methodNames(search.hierarchyOf(type("Chain.Leaf")).overriding(method("Chain.Leaf", "weight", 0))));
    }

    @Test
    @DisplayName("overridden: the nearest declaration overridden, past an overload, for local and anonymous classes in lambda bodies, an enum constant body and a type argument; null for a static method")
    void overridden() throws CoreException {
        ModelHierarchy leaf = search.hierarchyOf(type("Chain.Leaf"));
        assertAll(
            () -> assertEquals(P + "Chain$Base#put(String)", methodName(leaf.overridden(method("Chain.Leaf", "put", 1)))),
            () -> assertEquals(P + "Chain$Base#weight()", methodName(leaf.overridden(method("Chain.Leaf", "weight", 0)))),
            () -> assertNull(leaf.overridden(method("Chain.Leaf", "count", 0))),
            () -> assertNull(search.hierarchyOf(type("Chain.Middle")).overridden(method("Chain.Middle", "put", 2))),
            () -> assertEquals(P + "Chain$Sink#put(T)",
                methodName(search.hierarchyOf(type("Chain.Base")).overridden(method("Chain.Base", "put", 1)))),
            () -> assertEquals(P + "Locals$Local#put(String)",
                methodName(search.hierarchyOf(type("Locals$1")).overridden(method("Locals$1", "put", 1)))),
            () -> assertEquals(P + "Chain$Sink#put(T)",
                methodName(search.hierarchyOf(type("Locals$2")).overridden(method("Locals$2", "put", 1)))),
            () -> assertEquals(P + "Host$Mark#tag()",
                methodName(search.hierarchyOf(type("Host$2")).overridden(method("Host$2", "tag", 0)))),
            () -> assertEquals(P + "Host$Plate#size()",
                methodName(search.hierarchyOf(type("Host$Thin")).overridden(method("Host$Thin", "size", 0)))),
            () -> assertEquals(P + "Locals$Mode#shout()",
                methodName(search.hierarchyOf(type("Locals$Mode$1")).overridden(method("Locals$Mode$1", "shout", 0)))));
    }

    @Test
    @DisplayName("overridden: Ranked.compareTo(Base) overrides the JDK's Comparable.compareTo")
    void overriddenBinaryMethod() throws CoreException {
        IMethod compareTo = Arrays.stream(service.findType("java.lang.Comparable").getMethods())
            .filter(m -> m.getElementName().equals("compareTo")).findFirst().orElseThrow();
        assertEquals(compareTo,
            search.hierarchyOf(type("Chain.Ranked")).overridden(method("Chain.Ranked", "compareTo", 1)));
    }

    @Test
    @DisplayName("allOverridden: every method a method overrides, in the superclass chain and the interfaces, with type arguments applied; none for a static method or an interface method without superinterfaces")
    void allOverridden() throws CoreException {
        assertAll(
            () -> assertEquals(List.of(P + "Weave$Knot#tie()", P + "Weave$Loop#tie()"),
                methodNames(search.hierarchyOf(type("Weave.Hitch")).allOverridden(method("Weave.Hitch", "tie", 0)))),
            () -> assertEquals(List.of(P + "Chain$Sink#put(T)", P + "Chain$Base#put(String)"),
                methodNames(search.hierarchyOf(type("Chain.Leaf")).allOverridden(method("Chain.Leaf", "put", 1)))),
            () -> assertEquals(List.of(P + "Chain$Sink#put(T)", P + "Chain$Base#put(String)", P + "Locals$Local#put(String)"),
                methodNames(search.hierarchyOf(type("Locals$1")).allOverridden(method("Locals$1", "put", 1)))),
            () -> assertEquals(List.of(P + "Host$Plate#size()"),
                methodNames(search.hierarchyOf(type("Host$Thin")).allOverridden(method("Host$Thin", "size", 0)))),
            () -> assertEquals(List.of("java.lang.Comparable#compareTo(T)"),
                methodNames(search.hierarchyOf(type("Chain.Ranked")).allOverridden(method("Chain.Ranked", "compareTo", 1)))),
            () -> assertEquals(List.of(),
                methodNames(search.hierarchyOf(type("Chain.Leaf")).allOverridden(method("Chain.Leaf", "count", 0)))),
            () -> assertEquals(List.of(),
                methodNames(search.hierarchyOf(type("Host.Mark")).allOverridden(method("Host.Mark", "tag", 0)))));
    }

    @Test
    @DisplayName("joinedInSubtypes: Splice.join and Bend.join are one method of Sheet, which declares none; Peg<T>.hang(T) and Hook.hang(String) of Rack, a Peg<String>; Loop.tie and Knot.tie of Hitch; none for a method only overriding or overridden in its subtypes")
    void joinedInSubtypes() throws CoreException {
        assertAll(
            () -> assertEquals(List.of(P + "Weave$Bend#join(String)"),
                methodNames(search.hierarchyOf(type("Weave.Splice")).joinedInSubtypes(method("Weave.Splice", "join", 1)))),
            () -> assertEquals(List.of(P + "Weave$Splice#join(String)"),
                methodNames(search.hierarchyOf(type("Weave.Bend")).joinedInSubtypes(method("Weave.Bend", "join", 1)))),
            () -> assertEquals(List.of(P + "Weave$Hook#hang(String)"),
                methodNames(search.hierarchyOf(type("Weave.Peg")).joinedInSubtypes(method("Weave.Peg", "hang", 1)))),
            () -> assertEquals(List.of(P + "Weave$Peg#hang(T)"),
                methodNames(search.hierarchyOf(type("Weave.Hook")).joinedInSubtypes(method("Weave.Hook", "hang", 1)))),
            () -> assertEquals(List.of(P + "Weave$Knot#tie()"),
                methodNames(search.hierarchyOf(type("Weave.Loop")).joinedInSubtypes(method("Weave.Loop", "tie", 0)))),
            () -> assertEquals(List.of(),
                methodNames(search.hierarchyOf(type("Chain.Base")).joinedInSubtypes(method("Chain.Base", "put", 1)))),
            () -> assertEquals(List.of(),
                methodNames(search.hierarchyOf(type("Chain.Sink")).joinedInSubtypes(method("Chain.Sink", "put", 1)))),
            () -> assertEquals(List.of(),
                methodNames(search.hierarchyOf(type("Host.Mark")).joinedInSubtypes(method("Host.Mark", "tag", 0)))));
    }

    @Test
    @DisplayName("rippleOf: the closure of a method under allOverridden, overriding and joinedInSubtypes, never expanded from a class-file method")
    void rippleOf() throws CoreException {
        List<String> tie = List.of(P + "Weave$Knot#tie()", P + "Weave$Loop#tie()", P + "Weave$Hitch#tie()");
        List<String> join = List.of(P + "Weave$Bend#join(String)", P + "Weave$Splice#join(String)");
        List<String> put = List.of(P + "Chain$Sink#put(T)", P + "Chain$Base#put(String)", P + "Chain$Leaf#put(String)",
            P + "Locals$Named#put(String)", P + "Locals$Local#put(String)", P + "Locals$1#put(String)",
            P + "Locals$2#put(String)");
        assertAll(
            () -> assertEquals(tie, methodNames(search.rippleOf(method("Weave.Loop", "tie", 0)))),
            () -> assertEquals(tie, methodNames(search.rippleOf(method("Weave.Hitch", "tie", 0)))),
            () -> assertEquals(join, methodNames(search.rippleOf(method("Weave.Splice", "join", 1)))),
            () -> assertEquals(join, methodNames(search.rippleOf(method("Weave.Bend", "join", 1)))),
            () -> assertEquals(List.of(P + "Weave$Hook#hang(String)", P + "Weave$Peg#hang(T)"),
                methodNames(search.rippleOf(method("Weave.Hook", "hang", 1)))),
            () -> assertEquals(put, methodNames(search.rippleOf(method("Locals$1", "put", 1)))),
            () -> assertEquals(List.of(P + "Host$Mark#tag()", P + "Host$1#tag()", P + "Host$2#tag()", P + "Host$3#tag()",
                P + "Host$5#tag()"), methodNames(search.rippleOf(method("Host.Mark", "tag", 0)))),
            () -> assertEquals(List.of(P + "Chain$Ranked#compareTo(Base)", "java.lang.Comparable#compareTo(T)"),
                methodNames(search.rippleOf(method("Chain.Ranked", "compareTo", 1)))),
            () -> assertEquals(List.of(P + "Chain$Leaf#count()"), methodNames(search.rippleOf(method("Chain.Leaf", "count", 0)))));
    }

    @Test
    @DisplayName("A lambda expression has no hierarchy of its own, and a method of another type is refused")
    void refusals() throws CoreException {
        // The parameter item of the lambda quiet (Locals.java line 29, column 35) belongs to the
        // lambda's method, whose type is the lambda expression.
        IJavaElement item = service.getElementAtPosition(
            helper.getFixturePath("lambda-hierarchy").resolve("src/main/java/com/example/hierarchy/Locals.java"), 29, 35);
        IType quiet = (IType) item.getAncestor(IJavaElement.TYPE);
        assertEquals(true, quiet.isLambda(), "the type enclosing item is the lambda quiet: " + quiet);
        ModelHierarchy base = search.hierarchyOf(type("Chain.Base"));
        IMethod leafPut = method("Chain.Leaf", "put", 1);
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> search.hierarchyOf(quiet)),
            () -> assertThrows(IllegalArgumentException.class, () -> base.overriding(leafPut)),
            () -> assertThrows(IllegalArgumentException.class, () -> base.overridden(leafPut)),
            () -> assertThrows(IllegalArgumentException.class, () -> base.allOverridden(leafPut)),
            () -> assertThrows(IllegalArgumentException.class, () -> base.joinedInSubtypes(leafPut)));
    }

    /**
     * The source type named {@code P + typeQualifiedName}: a member type with '.' before its name,
     * a local or anonymous class in the model's '$' form.
     */
    private IType type(String typeQualifiedName) {
        IType type = service.findType(P + typeQualifiedName);
        if (type == null) {
            throw new AssertionError("No type " + P + typeQualifiedName);
        }
        return type;
    }

    /** The one method of the type with that name and number of parameters. */
    private IMethod method(String typeQualifiedName, String name, int parameters) throws JavaModelException {
        List<IMethod> methods = Arrays.stream(type(typeQualifiedName).getMethods())
            .filter(m -> m.getElementName().equals(name) && m.getNumberOfParameters() == parameters)
            .toList();
        assertEquals(1, methods.size(), "one " + name + "/" + parameters + " in " + typeQualifiedName);
        return methods.get(0);
    }

    private static List<String> names(String... names) {
        return List.of(names);
    }

    private static List<String> typeNames(List<IType> types) {
        return types.stream().map(IType::getFullyQualifiedName).toList();
    }

    /**
     * The names of the source types at the head of {@code types}, after checking that every type
     * after them is binary: the JDK's types follow the project's.
     */
    private static List<String> sourcePrefix(List<IType> types) {
        int sources = 0;
        while (sources < types.size() && !types.get(sources).isBinary()) {
            sources++;
        }
        for (IType after : types.subList(sources, types.size())) {
            assertEquals(true, after.isBinary(), "a source type after a binary one: " + after.getFullyQualifiedName());
        }
        return typeNames(types.subList(0, sources));
    }

    private static List<String> methodNames(List<IMethod> methods) {
        return methods.stream().map(SearchServiceHierarchyTest::methodName).toList();
    }

    /** {@code pkg.Type#name(SimpleParameterTypes)}. */
    private static String methodName(IMethod method) {
        if (method == null) {
            return null;
        }
        String parameters = Arrays.stream(method.getParameterTypes())
            .map(type -> Signature.getSimpleName(Signature.toString(type)))
            .collect(Collectors.joining(","));
        return method.getDeclaringType().getFullyQualifiedName() + "#" + method.getElementName() + "(" + parameters + ")";
    }
}
