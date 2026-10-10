package org.javalens.core.search;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.FailingElements;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The search fails, never answers short, when what it reads fails: a constructor's references
 * whose source units cannot be listed for the delegations JDT's index misses, and a match whose
 * element, or the model element at its place, cannot be read. Elements are simple-maven's, failing
 * as {@link FailingElements} makes them.
 */
class SearchServiceFailureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("the references of a constructor whose project's source units cannot be listed fail")
    void constructorReferences_unlistableUnits() {
        SearchService search = new SearchService(service.getJavaProject()) {
            @Override
            ICompilationUnit[] collectSourceCompilationUnits() throws CoreException {
                throw FailingElements.failure();
            }
        };
        IMethod constructor = service.findType("com.example.ConstructorTarget")
            .getMethod("ConstructorTarget", new String[] { "QString;" });
        CoreException failure = assertThrows(CoreException.class,
            () -> search.findReferences(constructor, IJavaSearchConstants.REFERENCES, SearchService.ALL_MATCHES));
        assertEquals(FailingElements.FAILURE, failure.getMessage());
    }

    @Test
    @DisplayName("a match whose element's kind cannot be read fails, naming the element")
    void match_unreadableElementKind() {
        IType calculator = FailingElements.failing(service.findType("com.example.Calculator"), IType.class, "isLocal");
        SearchMatch match = new SearchMatch(calculator, SearchMatch.A_ACCURATE, 0, 0,
            SearchEngine.getDefaultSearchParticipant(), calculator.getResource());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> SearchService.withModelElement(match));
        assertEquals("Cannot read the kind of Calculator: " + FailingElements.FAILURE, failure.getMessage());
    }

    @Test
    @DisplayName("a match in an anonymous class whose unit cannot give the element at its place fails, naming the place")
    void match_unreadableModelElement() throws Exception {
        ICompilationUnit unit = service.getCompilationUnit(Path.of("src/main/java/com/example/AnonymousClassExamples.java"));
        IType anonymous = firstAnonymous(unit);
        IType element = FailingElements.answering(anonymous, IType.class,
            Map.of("getAncestor", FailingElements.failing(unit, ICompilationUnit.class, "getElementAt")), Set.of());
        SearchMatch match = new SearchMatch(element, SearchMatch.A_ACCURATE, 0, 0,
            SearchEngine.getDefaultSearchParticipant(), anonymous.getResource());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> SearchService.withModelElement(match));
        assertEquals("Cannot read the element at offset 0 of AnonymousClassExamples.java: " + FailingElements.FAILURE,
            failure.getMessage());
    }

    /** The first anonymous class declared in a method of {@code unit}'s first type. */
    private static IType firstAnonymous(ICompilationUnit unit) throws Exception {
        for (IMethod method : unit.getTypes()[0].getMethods()) {
            for (IJavaElement child : method.getChildren()) {
                if (child instanceof IType type && type.isAnonymous()) {
                    return type;
                }
            }
        }
        throw new AssertionError("AnonymousClassExamples declares an anonymous class in a method");
    }
}
