package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins ElementKindResolver: every emitted kind is lowercase / camelCase and never
 * mixes capitalization with TypeKindResolver. Regression catches the B-6 pattern
 * where element-kind ("Method", "Field") and type-kind ("class", "interface") were
 * emitted into the same field with inconsistent casing.
 */
class ElementKindResolverTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("Method element resolves to lowercase 'method'")
    void method_isLowercase() throws Exception {
        IType calculator = service.findType("com.example.Calculator");
        assertNotNull(calculator);
        IMethod add = null;
        for (IMethod m : calculator.getMethods()) {
            if ("add".equals(m.getElementName())) {
                add = m;
                break;
            }
        }
        assertNotNull(add, "Calculator.add must exist");
        assertEquals("method", ElementKindResolver.kindOf(add));
    }

    @Test
    @DisplayName("Constructor element resolves to lowercase 'constructor'")
    void constructor_isLowercase() throws Exception {
        // HelloWorld declares a constructor.
        IType helloWorld = service.findType("com.example.HelloWorld");
        assertNotNull(helloWorld);
        IMethod ctor = null;
        for (IMethod m : helloWorld.getMethods()) {
            if (m.isConstructor()) {
                ctor = m;
                break;
            }
        }
        assertNotNull(ctor, "HelloWorld must declare a constructor");
        assertEquals("constructor", ElementKindResolver.kindOf(ctor));
    }

    @Test
    @DisplayName("Regular field resolves to lowercase 'field'")
    void regularField_isLowercase() throws Exception {
        IType calculator = service.findType("com.example.Calculator");
        IField lastResult = calculator.getField("lastResult");
        assertNotNull(lastResult);
        assertTrue(lastResult.exists());
        assertEquals("field", ElementKindResolver.kindOf(lastResult));
    }

    @Test
    @DisplayName("Static final field is classified as 'constant' (not 'field')")
    void staticFinalField_isConstant() throws Exception {
        // RefactoringTarget.MAX_SIZE is static final.
        IType refactoringTarget = service.findType("com.example.RefactoringTarget");
        IField maxSize = refactoringTarget.getField("MAX_SIZE");
        assertNotNull(maxSize);
        assertTrue(maxSize.exists(), "RefactoringTarget.MAX_SIZE must exist");
        assertEquals("constant", ElementKindResolver.kindOf(maxSize),
            "Static final field must classify as constant; got: " + ElementKindResolver.kindOf(maxSize));
    }

    @Test
    @DisplayName("Enum constant is classified as 'enumConstant'")
    void enumConstant_isEnumConstantKind() throws Exception {
        // TypeKindsFixture.Color has enum constants.
        IType color = service.findType("com.example.TypeKindsFixture.Color");
        if (color == null) {
            // Try with the $-form.
            color = service.findType("com.example.TypeKindsFixture$Color");
        }
        assertNotNull(color, "TypeKindsFixture.Color enum must exist");
        IField[] fields = color.getFields();
        IField first = null;
        for (IField f : fields) {
            if (f.isEnumConstant()) {
                first = f;
                break;
            }
        }
        assertNotNull(first, "TypeKindsFixture.Color must have at least one enum constant");
        assertEquals("enumConstant", ElementKindResolver.kindOf(first));
    }

    @Test
    @DisplayName("Type elements delegate to TypeKindResolver (class/interface/enum/record/annotation)")
    void type_delegatesToTypeKindResolver() throws Exception {
        IType calc = service.findType("com.example.Calculator");
        assertEquals("class", ElementKindResolver.kindOf(calc));
        IType shape = service.findType("com.example.IShape");
        assertEquals("interface", ElementKindResolver.kindOf(shape));
    }

    @Test
    @DisplayName("Local variable resolves to 'variable' (LOCAL_VARIABLE branch)")
    void localVariable_branch() throws Exception {
        // RefactoringTarget.processData has `String trimmed = input.trim();`
        // (0-based line 26, col 15 for the `trimmed` identifier).
        Path rt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java");
        IJavaElement local = service.getElementAtPosition(rt, 26, 15);
        assertNotNull(local, "Expected local variable at trimmed declaration; got null");
        assertEquals(IJavaElement.LOCAL_VARIABLE, local.getElementType(),
            "Fixture position must resolve to a LOCAL_VARIABLE element; got: " + local);
        assertEquals("variable", ElementKindResolver.kindOf(local));
    }

    @Test
    @DisplayName("Type parameter resolves to 'typeParameter' (TYPE_PARAMETER branch)")
    void typeParameter_branch() throws Exception {
        // TypeKindsFixture.BoundedBox is `public static class BoundedBox<N extends Number>`.
        // JdtContractTest pins that findType resolves nested types only via dotted form;
        // walk to BoundedBox via the parent type's getType(simpleName).
        IType outer = service.findType("com.example.TypeKindsFixture");
        assertNotNull(outer);
        IType boundedBox = outer.getType("BoundedBox");
        assertNotNull(boundedBox);
        assertTrue(boundedBox.exists(), "TypeKindsFixture.BoundedBox must exist");
        ITypeParameter n = boundedBox.getTypeParameter("N");
        assertNotNull(n);
        assertTrue(n.exists(), "BoundedBox<N> must declare type parameter N");
        assertEquals(IJavaElement.TYPE_PARAMETER, n.getElementType());
        assertEquals("typeParameter", ElementKindResolver.kindOf(n));
    }

    @Test
    @DisplayName("Package fragment resolves to 'package' (PACKAGE_FRAGMENT branch)")
    void packageFragment_branch() throws Exception {
        IType calc = service.findType("com.example.Calculator");
        assertNotNull(calc);
        IPackageFragment pkg = calc.getPackageFragment();
        assertNotNull(pkg);
        assertEquals(IJavaElement.PACKAGE_FRAGMENT, pkg.getElementType());
        assertEquals("com.example", pkg.getElementName());
        assertEquals("package", ElementKindResolver.kindOf(pkg));
    }

    @Test
    @DisplayName("Package declaration resolves to 'packageDeclaration' (PACKAGE_DECLARATION branch)")
    void packageDeclaration_branch() throws Exception {
        IType calc = service.findType("com.example.Calculator");
        ICompilationUnit cu = calc.getCompilationUnit();
        assertNotNull(cu);
        // Calculator.java declares `package com.example;` at the top — exactly one
        // IPackageDeclaration on the CU.
        IPackageDeclaration[] decls = cu.getPackageDeclarations();
        assertEquals(1, decls.length,
            "Calculator.java must have exactly one package declaration; got: " + decls.length);
        IPackageDeclaration decl = decls[0];
        assertEquals(IJavaElement.PACKAGE_DECLARATION, decl.getElementType());
        assertEquals("packageDeclaration", ElementKindResolver.kindOf(decl));
    }

    @Test
    @DisplayName("Compilation unit resolves to 'compilationUnit' (COMPILATION_UNIT branch)")
    void compilationUnit_branch() throws Exception {
        IType calc = service.findType("com.example.Calculator");
        ICompilationUnit cu = calc.getCompilationUnit();
        assertNotNull(cu);
        assertEquals(IJavaElement.COMPILATION_UNIT, cu.getElementType());
        assertEquals("compilationUnit", ElementKindResolver.kindOf(cu));
    }

    @Test
    @DisplayName("Static initializer resolves to 'initializer' (INITIALIZER branch)")
    void initializer_branch() throws Exception {
        // StaticInitFixture is a one-off fixture for this branch: it carries
        // `static { counter = 42; }`. IType.getInitializer(n) returns the n-th
        // initializer block; .exists() flips true once the block is present.
        IType fixture = service.findType("com.example.StaticInitFixture");
        assertNotNull(fixture, "StaticInitFixture must exist; check fixture is present in test-resources");
        IInitializer init = fixture.getInitializer(1);
        assertNotNull(init);
        assertTrue(init.exists(),
            "First initializer block on StaticInitFixture must exist (it has `static {}`)");
        assertEquals(IJavaElement.INITIALIZER, init.getElementType());
        assertEquals("initializer", ElementKindResolver.kindOf(init));
    }

    @Test
    @DisplayName("Import declaration resolves to 'import' (IMPORT_DECLARATION branch)")
    void importDeclaration_branch() throws Exception {
        // RefactoringTarget.java imports java.util.List (and others) at the top of the file.
        IType rt = service.findType("com.example.RefactoringTarget");
        ICompilationUnit cu = rt.getCompilationUnit();
        IImportDeclaration[] imports = cu.getImports();
        assertTrue(imports.length > 0,
            "RefactoringTarget.java must have at least one import; got: " + imports.length);
        IImportDeclaration first = imports[0];
        assertEquals(IJavaElement.IMPORT_DECLARATION, first.getElementType());
        assertEquals("import", ElementKindResolver.kindOf(first));
    }

    @Test
    @DisplayName("Unrecognized element type falls through to 'unknown' (default branch)")
    void default_branch_unknown() throws Exception {
        // IJavaProject is an IJavaElement (type=JAVA_PROJECT=2) — not handled by any
        // branch in kindOf, so the switch default fires and returns "unknown". This
        // is the only branch we can exercise with a guaranteed-available concrete
        // IJavaElement; the IType/IMethod/IField/etc instanceof guards run before the
        // switch, so we need something that is none of those AND has a non-matched
        // elementType.
        IJavaProject project = service.getJavaProject();
        assertNotNull(project);
        assertEquals(IJavaElement.JAVA_PROJECT, project.getElementType());
        assertEquals("unknown", ElementKindResolver.kindOf(project),
            "JAVA_PROJECT is not in the explicit switch cases — must fall through to 'unknown'");
    }

    @Test
    @DisplayName("All emitted values are lowercase / camelCase (no leading uppercase letter)")
    void allValuesAreLowercase() throws Exception {
        // Sample the full enumeration of elements we can resolve; assert none start uppercase.
        IType calc = service.findType("com.example.Calculator");
        String[] samples = {
            ElementKindResolver.kindOf(calc),
            ElementKindResolver.kindOf(calc.getMethods()[0]),
            ElementKindResolver.kindOf(calc.getField("lastResult")),
            ElementKindResolver.fieldKindOf(calc.getField("lastResult"))
        };
        for (String s : samples) {
            assertNotNull(s);
            char c = s.charAt(0);
            assertTrue(Character.isLowerCase(c),
                "kind value must start lowercase; got: '" + s + "' (first char: '" + c + "')");
        }
    }
}
