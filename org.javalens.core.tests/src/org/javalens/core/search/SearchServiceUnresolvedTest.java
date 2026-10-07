package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.search.ModelHierarchy.UnresolvedSupertype;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SearchService keeps the matches JDT's search confirms apart from the places it could not
 * confirm, and ModelHierarchy reports the supertypes the compiler cannot resolve and the subtypes
 * search could not confirm.
 *
 * <p>potential-matches: Client (com.example.potential) calls Store.fill at lines 14, 16 (a method
 * reference) and 25, and IntConsumer.accept at line 17; Stray (com.example.stray), which imports the
 * missing org.absent.Ledger and nothing of com.example.potential, calls fill on a Ledger at line 14
 * and refers to ledger::fill at line 17, implements Probe, which does not resolve there, at line 7,
 * and declares use(Ledger, Object) at line 13, as Client declares use(Store, Object). JDT reports
 * each of Stray's places as A_INACCURATE (MethodLocator.resolveLevel(MessageSend) :798-810,
 * resolveLevel(ReferenceExpression) :849-852, SuperTypeReferenceLocator.resolveLevel(ASTNode)
 * :115), and Stray's use for a query naming Client: Stray's missing interface may make it Client's
 * subtype (MethodLocator.resolveLevelAsSubtype :895-945, PatternLocator.resolveLevelForType
 * :825-827).
 *
 * <p>unresolved-supertypes: Orphan extends Base implements Shape, Runnable, where Base and Shape do
 * not resolve; Heir extends Orphan implements Comparable&lt;Heir&gt;; the interface Partial extends
 * Shape, Runnable. Lines are zero-based.
 */
class SearchServiceUnresolvedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @Test
    @DisplayName("findAllReferences of Store.fill: Client's 3 references are matches, Stray's 2 are potential matches")
    void referencesSplitByAccuracy() throws CoreException {
        service = helper.loadProject("potential-matches");
        SearchResult result = service.getSearchService().findAllReferences(method("com.example.potential.Store", "fill"),
            SearchService.ALL_MATCHES);
        assertAll(
            () -> assertEquals(List.of("Client.java:14", "Client.java:16", "Client.java:25"), places(result.matches())),
            () -> assertEquals(3, result.totalEncountered()),
            () -> assertEquals(List.of("Stray.java:14", "Stray.java:17"), places(result.potentialMatches())),
            () -> assertEquals(2, result.potentialTotal()),
            () -> assertEquals(false, result.truncated()));
    }

    @Test
    @DisplayName("findAllReferences of Store.fill with maxResults 1 keeps the first match and the first potential match, each with its exact count")
    void referencesCut() throws CoreException {
        service = helper.loadProject("potential-matches");
        SearchResult result = service.getSearchService().findAllReferences(method("com.example.potential.Store", "fill"), 1);
        assertAll(
            () -> assertEquals(List.of("Client.java:14"), places(result.matches())),
            () -> assertEquals(3, result.totalEncountered()),
            () -> assertEquals(List.of("Stray.java:14"), places(result.potentialMatches())),
            () -> assertEquals(2, result.potentialTotal()),
            () -> assertEquals(true, result.truncated()));
    }

    @Test
    @DisplayName("findAllReferences of IntConsumer.accept, a class-file method, finds the project's one call and none in the JDK")
    void classFileMethodReferences() throws CoreException {
        service = helper.loadProject("potential-matches");
        IMethod accept = service.findType("java.util.function.IntConsumer").getMethod("accept", new String[] { "I" });
        SearchResult result = service.getSearchService().findAllReferences(accept, SearchService.ALL_MATCHES);
        assertAll(
            () -> assertEquals(List.of("Client.java:17"), places(result.matches())),
            () -> assertEquals(1, result.totalEncountered()),
            () -> assertEquals(List.of(), places(result.potentialMatches())),
            () -> assertEquals(0, result.potentialTotal()));
    }

    @Test
    @DisplayName("searchSymbols Client.use: Client's use is a declaration, Stray's use, in a class that may be Client's subtype, a potential one")
    void declarationInPotentialSubtype() throws CoreException {
        service = helper.loadProject("potential-matches");
        SearchResult result = service.getSearchService().searchSymbols("Client.use", IJavaSearchConstants.METHOD, 50);
        assertAll(
            () -> assertEquals(List.of("Client.java:13"), places(result.matches())),
            () -> assertEquals(1, result.totalEncountered()),
            () -> assertEquals(List.of("Stray.java:13"), places(result.potentialMatches())),
            () -> assertEquals(1, result.potentialTotal()));
    }

    @Test
    @DisplayName("Probe's subtypes: Client; Stray, whose Probe does not resolve, is a potential subtype")
    void potentialSubtypes() throws CoreException {
        service = helper.loadProject("potential-matches");
        ModelHierarchy probe = service.getSearchService().hierarchyOf(service.findType("com.example.potential.Probe"));
        assertAll(
            () -> assertEquals(List.of("com.example.potential.Client"), typeNames(probe.allSubtypes())),
            () -> assertEquals(List.of("Stray.java:7"), places(probe.potentialSubtypes())));
    }

    @Test
    @DisplayName("Orphan: no superclass that resolves, superclasses Object only, interface Runnable; Base and Shape do not resolve")
    void unresolvedDirectSupertypes() throws CoreException {
        service = helper.loadProject("unresolved-supertypes");
        ModelHierarchy orphan = service.getSearchService().hierarchyOf(service.findType("com.example.orphan.Orphan"));
        assertAll(
            () -> assertNull(orphan.superclass()),
            () -> assertEquals(List.of("java.lang.Runnable"), typeNames(orphan.superInterfaces())),
            () -> assertEquals(List.of("java.lang.Object"), typeNames(orphan.allSuperclasses())),
            () -> assertEquals(List.of("java.lang.Runnable"), typeNames(orphan.allSuperInterfaces())),
            () -> assertEquals(List.of("java.lang.Runnable", "java.lang.Object"), typeNames(orphan.allSupertypes())),
            () -> assertEquals(List.of("Base in com.example.orphan.Orphan", "Shape in com.example.orphan.Orphan"),
                unresolved(orphan.unresolvedSupertypes())),
            () -> assertEquals(List.of("com.example.orphan.Heir"), typeNames(orphan.allSubtypes())));
    }

    @Test
    @DisplayName("Heir: superclasses Orphan then Object, interfaces Comparable then Runnable; Orphan's Base and Shape do not resolve")
    void unresolvedInheritedSupertypes() throws CoreException {
        service = helper.loadProject("unresolved-supertypes");
        ModelHierarchy heir = service.getSearchService().hierarchyOf(service.findType("com.example.orphan.Heir"));
        assertAll(
            () -> assertEquals("com.example.orphan.Orphan", heir.superclass().getFullyQualifiedName()),
            () -> assertEquals(List.of("com.example.orphan.Orphan", "java.lang.Object"),
                typeNames(heir.allSuperclasses())),
            () -> assertEquals(List.of("java.lang.Comparable", "java.lang.Runnable"),
                typeNames(heir.allSuperInterfaces())),
            () -> assertEquals(List.of("java.lang.Comparable", "com.example.orphan.Orphan", "java.lang.Runnable",
                "java.lang.Object"), typeNames(heir.allSupertypes())),
            () -> assertEquals(List.of("Base in com.example.orphan.Orphan", "Shape in com.example.orphan.Orphan"),
                unresolved(heir.unresolvedSupertypes())));
    }

    @Test
    @DisplayName("Partial, an interface: interface Runnable, no superclasses; Shape does not resolve")
    void unresolvedSuperinterface() throws CoreException {
        service = helper.loadProject("unresolved-supertypes");
        ModelHierarchy partial = service.getSearchService().hierarchyOf(service.findType("com.example.orphan.Partial"));
        assertAll(
            () -> assertNull(partial.superclass()),
            () -> assertEquals(List.of("java.lang.Runnable"), typeNames(partial.superInterfaces())),
            () -> assertEquals(List.of(), typeNames(partial.allSuperclasses())),
            () -> assertEquals(List.of("Shape in com.example.orphan.Partial"), unresolved(partial.unresolvedSupertypes())));
    }

    @Test
    @DisplayName("Runnable, a class-file type whose supertypes all resolve, reports no unresolved supertype")
    void classFileSupertypesResolve() throws CoreException {
        service = helper.loadProject("unresolved-supertypes");
        ModelHierarchy runnable = service.getSearchService().hierarchyOf(service.findType("java.lang.Runnable"));
        assertEquals(List.of(), unresolved(runnable.unresolvedSupertypes()));
    }

    @Test
    @DisplayName("java.lang.Object: Orphan and Heir are source subtypes, neither a direct one, their direct superclasses being Base and Orphan")
    void objectSubtypesThroughUnresolvedSuperclass() throws CoreException {
        service = helper.loadProject("unresolved-supertypes");
        ModelHierarchy object = service.getSearchService().hierarchyOf(service.findType("java.lang.Object"));
        assertAll(
            () -> assertEquals(List.of(), sourceNames(object.subtypes())),
            () -> assertEquals(List.of("com.example.orphan.Heir", "com.example.orphan.Orphan"),
                sourceNames(object.allSubtypes())));
    }

    private IMethod method(String typeName, String name) throws CoreException {
        for (IMethod method : service.findType(typeName).getMethods()) {
            if (method.getElementName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("No method " + name + " in " + typeName);
    }

    /** Each match as {@code File.java:line}, the line zero-based. */
    private List<String> places(List<SearchMatch> matches) {
        return matches.stream().map(match -> {
            ICompilationUnit unit = (ICompilationUnit) ((IJavaElement) match.getElement())
                .getAncestor(IJavaElement.COMPILATION_UNIT);
            return unit.getElementName() + ":" + service.getLineNumber(unit, match.getOffset());
        }).toList();
    }

    private static List<String> typeNames(List<IType> types) {
        return types.stream().map(IType::getFullyQualifiedName).toList();
    }

    /** The source types among {@code types}, by name. */
    private static List<String> sourceNames(List<IType> types) {
        return types.stream().filter(type -> !type.isBinary()).map(IType::getFullyQualifiedName).toList();
    }

    private static List<String> unresolved(List<UnresolvedSupertype> supertypes) {
        return supertypes.stream()
            .map(supertype -> supertype.name() + " in " + supertype.declaringType().getFullyQualifiedName())
            .toList();
    }
}
