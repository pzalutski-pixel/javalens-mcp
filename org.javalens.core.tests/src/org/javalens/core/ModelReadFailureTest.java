package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.FailingElements;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A Java element JDT cannot read is a failure of the answer that needs it, never a default that
 * reads as a fact: a position or a line read from a source JDT cannot give is no line 0, column 0
 * or empty line; an element at a position read from a unit JDT cannot reconcile is no "nothing
 * there"; a kind read from an element JDT cannot read is no "class", "method" or "field". Each
 * failure names what could not be read and carries JDT's message. The elements are simple-maven's
 * Calculator (Calculator.java), its method add and its field lastResult, failing as
 * {@link FailingElements} makes them.
 */
class ModelReadFailureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String UNREADABLE = "Cannot read the source of Calculator.java: " + FailingElements.FAILURE;
    private static final String NO_SOURCE = "JDT gave no source for Calculator.java";

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("a line, column, offset or context line read from a unit whose source JDT cannot read fails, naming the unit")
    void positions_unreadableSource() {
        ICompilationUnit unit = FailingElements.failing(calculator(), ICompilationUnit.class, "getSource");
        assertAll(
            () -> assertFails(UNREADABLE, () -> service.getLineNumber(unit, 0)),
            () -> assertFails(UNREADABLE, () -> service.getColumnNumber(unit, 0)),
            () -> assertFails(UNREADABLE, () -> service.getContextLine(unit, 0)),
            () -> assertFails(UNREADABLE, () -> service.getOffset(unit, 0, 0)));
    }

    @Test
    @DisplayName("a line, column, offset or context line read from a unit JDT gives no source fails, naming the unit")
    void positions_noSource() {
        ICompilationUnit unit = FailingElements.withoutSource(calculator());
        assertAll(
            () -> assertFails(NO_SOURCE, () -> service.getLineNumber(unit, 0)),
            () -> assertFails(NO_SOURCE, () -> service.getColumnNumber(unit, 0)),
            () -> assertFails(NO_SOURCE, () -> service.getContextLine(unit, 0)),
            () -> assertFails(NO_SOURCE, () -> service.getOffset(unit, 0, 0)));
    }

    @Test
    @DisplayName("the element at a position of a unit JDT cannot reconcile fails, naming the position and the unit")
    void elementAtPosition_unreconcilableUnit() throws Exception {
        JdtServiceImpl failing = new JdtServiceImpl() {
            @Override
            public ICompilationUnit getCompilationUnit(Path filePath) {
                ICompilationUnit unit = super.getCompilationUnit(filePath);
                return unit == null ? null : FailingElements.failing(unit, ICompilationUnit.class, "reconcile");
            }
        };
        failing.loadProject(helper.getFixturePath("simple-maven"));
        assertFails("Cannot read the element at line 5, column 13 of Calculator.java: " + FailingElements.FAILURE,
            () -> failing.getElementAtPosition(Path.of(CALCULATOR), 5, 13));
    }

    @Test
    @DisplayName("the kind of a type JDT cannot read fails, naming the type")
    void typeKind_unreadable() {
        IType type = FailingElements.failing(service.findType("com.example.Calculator"), IType.class, "isAnnotation");
        assertFails("Cannot read the kind of Calculator: " + FailingElements.FAILURE, () -> TypeKindResolver.kindOf(type));
    }

    @Test
    @DisplayName("the kind of a method JDT cannot read fails, naming the method")
    void methodKind_unreadable() {
        IMethod add = service.findType("com.example.Calculator").getMethod("add", new String[] { "I", "I" });
        IMethod method = FailingElements.failing(add, IMethod.class, "isConstructor");
        assertFails("Cannot read the kind of add: " + FailingElements.FAILURE, () -> ElementKindResolver.kindOf(method));
    }

    @Test
    @DisplayName("the kind of a field JDT cannot read fails, naming the field")
    void fieldKind_unreadable() {
        IField lastResult = service.findType("com.example.Calculator").getField("lastResult");
        assertAll(
            () -> assertFails("Cannot read the kind of lastResult: " + FailingElements.FAILURE,
                () -> ElementKindResolver.fieldKindOf(FailingElements.failing(lastResult, IField.class, "isEnumConstant"))),
            () -> assertFails("Cannot read the kind of lastResult: " + FailingElements.FAILURE,
                () -> ElementKindResolver.kindOf(FailingElements.failing(lastResult, IField.class, "getFlags"))));
    }

    private ICompilationUnit calculator() {
        return service.getCompilationUnit(Path.of(CALCULATOR));
    }

    private static void assertFails(String message, Executable read) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, read);
        assertEquals(message, failure.getMessage());
    }
}
