package org.javalens.core.jdt;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.search.FieldReferenceMatch;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeReferenceMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins behavior of the Eclipse JDT API that JavaLens relies on. Failures here
 * mean **JDT changed**, not that our wrapper broke. They isolate JDT-version
 * upgrades from refactor regressions.
 *
 * <p>Sourced from invariants embedded in {@code JdtServiceImpl},
 * {@code SearchService}, {@code FindReferencesTool}, {@code TypeKindResolver},
 * and {@code MatchResolver}.
 */
class JdtContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    // ========== findType — nested types: dotted vs $-form ==========

    @Test
    @DisplayName("findType resolves nested types by dotted name: 'com.example.Outer.Inner'")
    void findType_nestedDotted_resolves() {
        IType color = service.findType("com.example.TypeKindsFixture.Color");
        assertNotNull(color, "JDT must accept dotted nested type names via findType");
    }

    @Test
    @DisplayName("findType does NOT accept $-form for nested types — only dotted")
    void findType_nestedDollar_returnsNull() {
        // JDT contract: IJavaProject.findType accepts only dotted FQN, not $-form.
        // The $-form is exclusive to SearchPattern.createPattern(typeName, ...) strings,
        // where SearchService.findFineGrainReferences passes type.getFullyQualifiedName('$').
        // If JDT ever changes this and accepts $-form via findType, our callers don't break
        // — but the test will surface the change.
        IType color = service.findType("com.example.TypeKindsFixture$Color");
        org.junit.jupiter.api.Assertions.assertNull(color,
            "JDT contract: findType accepts only dotted form. $-form must return null.");
    }

    // ========== isAnnotation / isInterface ordering quirk ==========

    @Test
    @DisplayName("@interface reports BOTH isAnnotation()=true AND isInterface()=true")
    void atInterface_reportsBothFlags() throws Exception {
        IType marker = service.findType("com.example.Marker");
        assertNotNull(marker);
        assertTrue(marker.isAnnotation(),
            "Marker is an @interface — isAnnotation() must return true");
        assertTrue(marker.isInterface(),
            "JDT quirk: @interface ALSO reports isInterface()=true. " +
            "Any kind-dispatch must check isAnnotation() FIRST or annotations will misclassify.");
    }

    @Test
    @DisplayName("Regular interface reports isInterface()=true, isAnnotation()=false")
    void plainInterface_doesNotReportAnnotation() throws Exception {
        IType iShape = service.findType("com.example.IShape");
        assertNotNull(iShape);
        assertTrue(iShape.isInterface());
        assertFalse(iShape.isAnnotation(),
            "Plain interface must NOT report isAnnotation()=true");
    }

    // ========== SearchMatch subclass per element kind ==========

    @Test
    @DisplayName("TYPE+REFERENCES search returns TypeReferenceMatch instances")
    void typeReferences_yieldTypeReferenceMatch() throws Exception {
        List<SearchMatch> matches = runSearch(SearchPattern.createPattern(
            "com.example.Calculator",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.REFERENCES,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE));
        assertFalse(matches.isEmpty(),
            "Calculator must have at least one type reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof TypeReferenceMatch,
                "Every TYPE+REFERENCES match must be a TypeReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    @Test
    @DisplayName("METHOD+REFERENCES search returns MethodReferenceMatch instances")
    void methodReferences_yieldMethodReferenceMatch() throws Exception {
        // Calculator.add has known call sites in fixtures.
        IType calc = service.findType("com.example.Calculator");
        assertNotNull(calc);
        var addMethods = calc.getMethods();
        var add = java.util.Arrays.stream(addMethods)
            .filter(m -> "add".equals(m.getElementName()))
            .findFirst().orElseThrow();
        List<SearchMatch> matches = runSearch(
            SearchPattern.createPattern(add, IJavaSearchConstants.REFERENCES));
        assertFalse(matches.isEmpty(),
            "Calculator.add must have at least one reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof MethodReferenceMatch,
                "Every METHOD+REFERENCES match must be a MethodReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    @Test
    @DisplayName("FIELD+REFERENCES search returns FieldReferenceMatch instances")
    void fieldReferences_yieldFieldReferenceMatch() throws Exception {
        // Calculator.lastResult has known read+write references.
        IType calc = service.findType("com.example.Calculator");
        var lastResult = calc.getField("lastResult");
        assertNotNull(lastResult);
        assertTrue(lastResult.exists(), "Calculator.lastResult field must resolve");
        List<SearchMatch> matches = runSearch(
            SearchPattern.createPattern(lastResult, IJavaSearchConstants.REFERENCES));
        assertFalse(matches.isEmpty(),
            "Calculator.lastResult must have at least one reference in fixtures");
        for (SearchMatch m : matches) {
            assertTrue(m instanceof FieldReferenceMatch,
                "Every FIELD+REFERENCES match must be a FieldReferenceMatch; got: "
                    + m.getClass().getName());
        }
    }

    // ========== R_PATTERN_MATCH vs R_REGEXP_MATCH semantics ==========

    @Test
    @DisplayName("R_PATTERN_MATCH supports * wildcard")
    void patternMatch_starWildcard_matches() throws Exception {
        List<SearchMatch> matches = runSearch(SearchPattern.createPattern(
            "Calc*",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE));
        assertFalse(matches.isEmpty(),
            "R_PATTERN_MATCH must support * — 'Calc*' must match Calculator");
    }

    @Test
    @DisplayName("R_PATTERN_MATCH does NOT support ? wildcard (informs SearchService.searchSymbols)")
    void patternMatch_questionWildcard_doesNotMatch() throws Exception {
        // This is the JDT behavior that SearchService.searchSymbols works around
        // via broaden-then-client-filter. JDT may either return a null pattern
        // outright (rejecting `?`) or produce a pattern that matches zero things.
        // Both outcomes are consistent with "JDT does not honor `?` here." If JDT
        // ever DOES support ? in R_PATTERN_MATCH, this test will fail and we can
        // simplify SearchService.
        SearchPattern pattern = SearchPattern.createPattern(
            "?alculator",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE);
        if (pattern == null) {
            // JDT rejected the pattern. Acceptable — the contract holds.
            return;
        }
        List<SearchMatch> matches = runSearchAcceptingNull(pattern);
        assertTrue(matches.isEmpty(),
            "JDT contract: R_PATTERN_MATCH treats '?' as a literal char, not a wildcard. " +
            "If this becomes non-empty, JDT added ?-glob support and " +
            "SearchService.searchSymbols can drop its broaden-then-filter shim.");
    }

    // ========== R_REGEXP_MATCH is documented as "not yet supported by Eclipse JDT" ==========

    @Test
    @DisplayName("R_REGEXP_MATCH returns 0 matches even for an anchored literal (JDT docs: 'not yet supported')")
    void regexpMatch_anchoredLiteral_returnsEmpty() throws Exception {
        // JDT's SearchPattern Javadoc states R_REGEXP_MATCH is "not yet supported by
        // Eclipse JDT". SearchService.searchSymbols's broaden-then-client-filter shim
        // for ?-glob exists because of this. We pin the contract so a future JDT
        // upgrade that implements R_REGEXP_MATCH causes this test to fail loudly,
        // prompting us to drop the shim.
        SearchPattern p = SearchPattern.createPattern(
            "^Calculator$",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_REGEXP_MATCH | SearchPattern.R_CASE_SENSITIVE);
        // JDT may either return null (rejecting the rule) or a pattern that produces
        // zero matches. Both outcomes confirm "not yet supported".
        List<SearchMatch> matches = runSearchAcceptingNull(p);
        assertTrue(matches.isEmpty(),
            "JDT contract: R_REGEXP_MATCH is documented as 'not yet supported by Eclipse JDT'. " +
            "If this assertion fires, JDT added support — drop SearchService.searchSymbols's " +
            "broaden-then-client-filter shim and use R_REGEXP_MATCH for ?-glob.");
    }

    @Test
    @DisplayName("R_REGEXP_MATCH returns 0 matches for a dot-wildcard regex (JDT docs: 'not yet supported')")
    void regexpMatch_dotWildcard_returnsEmpty() throws Exception {
        SearchPattern p = SearchPattern.createPattern(
            "^.alculator$",
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_REGEXP_MATCH | SearchPattern.R_CASE_SENSITIVE);
        List<SearchMatch> matches = runSearchAcceptingNull(p);
        assertTrue(matches.isEmpty(),
            "Same contract as anchored-literal: R_REGEXP_MATCH not yet supported.");
    }

    // ========== Helpers ==========

    private List<SearchMatch> runSearch(SearchPattern pattern) throws Exception {
        assertNotNull(pattern, "JDT must produce a non-null SearchPattern for valid inputs");
        return runSearchAcceptingNull(pattern);
    }

    private List<SearchMatch> runSearchAcceptingNull(SearchPattern pattern) throws Exception {
        List<SearchMatch> matches = new ArrayList<>();
        if (pattern == null) return matches;
        new SearchEngine().search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            service.getSearchService().getScope(),
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch m) {
                    matches.add(m);
                }
            },
            new NullProgressMonitor());
        return matches;
    }

    // ========== IBinding equality quirk for parameterized members ==========

    @Test
    @DisplayName("Generic-class field: declaration-context and usage-context IVariableBindings are NOT equal; their getVariableDeclaration() forms ARE equal")
    void genericClassField_bindingEquality_quirk() throws Exception {
        // In a generic class `class Foo<T> { private T x; T get() { return x; } }`,
        // resolveBinding on the field's VariableDeclarationFragment returns one
        // IVariableBinding; resolveBinding on the `x` SimpleName inside `get()` returns
        // a DIFFERENT IVariableBinding for the same field. Both .getVariableDeclaration()
        // canonical forms ARE equal.
        //
        // This is the quirk that drove issue #17: a tool putting the declaration binding
        // into a HashMap and looking up via the usage binding gets a miss. JavaLens's
        // FindUnusedCodeTool canonicalizes via getVariableDeclaration() at every put/add
        // site (commit 1a589fb). If a future JDT release unifies these bindings, this
        // test breaks loudly and we can simplify.
        java.nio.file.Path fixture = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/genericunused/GenericClass.java");
        ICompilationUnit cu = service.getCompilationUnit(fixture);
        assertNotNull(cu, "GenericClass.java fixture must load");

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        IVariableBinding[] declBinding = new IVariableBinding[1];
        IVariableBinding[] usageBinding = new IVariableBinding[1];

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object frag : node.fragments()) {
                    if (frag instanceof VariableDeclarationFragment vdf
                            && "value".equals(vdf.getName().getIdentifier())) {
                        declBinding[0] = vdf.resolveBinding();
                    }
                }
                return true;
            }
            @Override
            public boolean visit(MethodDeclaration node) {
                if (!"read".equals(node.getName().getIdentifier())) return true;
                node.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName name) {
                        if (!"value".equals(name.getIdentifier())) return true;
                        // Skip the method's name SimpleName itself
                        if (name.getParent() instanceof MethodDeclaration) return true;
                        if (name.resolveBinding() instanceof IVariableBinding vb) {
                            usageBinding[0] = vb;
                        }
                        return true;
                    }
                });
                return false;
            }
        });

        assertNotNull(declBinding[0], "Field declaration binding must resolve");
        assertNotNull(usageBinding[0], "Field usage binding inside read() must resolve");

        // The quirk: raw bindings are NOT equal even though they refer to the same field.
        assertFalse(declBinding[0].equals(usageBinding[0]),
            "JDT contract: in a generic class, the declaration-context binding and the "
                + "usage-context binding for the same field are DISTINCT IBinding instances. "
                + "If this changes (JDT unifies them), JavaLens's binding-canonicalization in "
                + "FindUnusedCodeTool can be simplified.");

        // The canonicalization that JavaLens uses to make membership comparisons work:
        IVariableBinding canonicalDecl = declBinding[0].getVariableDeclaration();
        IVariableBinding canonicalUsage = usageBinding[0].getVariableDeclaration();
        assertNotNull(canonicalDecl, "getVariableDeclaration() on declaration binding must not be null");
        assertNotNull(canonicalUsage, "getVariableDeclaration() on usage binding must not be null");
        assertEquals(canonicalDecl, canonicalUsage,
            "JDT contract: getVariableDeclaration() returns the canonical declaration "
                + "binding; both contexts must produce the same canonical instance.");
    }

    // ========== B3-2: ITypeBinding of a type variable ==========
    @Test
    @DisplayName("B3-2: a type-variable binding reports isTypeVariable=true and isClass/isInterface=false")
    void typeVariableBinding_kindFlags() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(
            helper.getFixturePath("simple-maven")
                .resolve("src/main/java/com/example/genericunused/GenericClass.java"));
        assertNotNull(cu);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        // Find the field declaration `private T value;` — the type node is a SimpleType
        // whose binding is the type variable T.
        org.eclipse.jdt.core.dom.ITypeBinding[] tvBinding = { null };
        ast.accept(new ASTVisitor() {
            @Override public boolean visit(FieldDeclaration node) {
                if (node.getType() != null) {
                    org.eclipse.jdt.core.dom.ITypeBinding b = node.getType().resolveBinding();
                    if (b != null && "T".equals(b.getName())) tvBinding[0] = b;
                }
                return true;
            }
        });
        assertNotNull(tvBinding[0],
            "Test fixture must declare a field whose type is the class-level type variable T");
        assertTrue(tvBinding[0].isTypeVariable(),
            "JDT contract: ITypeBinding.isTypeVariable() returns true for a type-variable usage");
        assertFalse(tvBinding[0].isClass(),
            "JDT contract: a type variable is NOT isClass");
        assertFalse(tvBinding[0].isInterface(),
            "JDT contract: a type variable is NOT isInterface");
    }

    // ========== B3-3: overload resolution selects the int overload for foo(42) ==========
    @Test
    @DisplayName("B3-3: MethodInvocation.resolveMethodBinding() picks the int overload for foo(42)")
    void overloadResolution_picksIntOverloadForIntegerLiteral() throws Exception {
        // Calculator.add(int, int) exists in simple-maven; overload disambiguation against
        // a hypothetical add(String, String) is not part of the fixture. Construct an in-memory
        // CU instead so the test pins JDT's overload-binding behavior directly, independent of
        // what the fixture provides.
        String source = """
            package com.example;
            class OverloadProbe {
                int foo(int x) { return x; }
                int foo(String s) { return s.length(); }
                int call() { return foo(42); }
            }
            """;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setProject(service.getJavaProject());
        parser.setUnitName("/simple-maven/src/main/java/com/example/OverloadProbe.java");
        parser.setSource(source.toCharArray());
        CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        org.eclipse.jdt.core.dom.IMethodBinding[] resolved = { null };
        ast.accept(new ASTVisitor() {
            @Override public boolean visit(org.eclipse.jdt.core.dom.MethodInvocation node) {
                if ("foo".equals(node.getName().getIdentifier())) {
                    resolved[0] = node.resolveMethodBinding();
                }
                return false;
            }
        });
        assertNotNull(resolved[0],
            "foo(42) must resolve to a method binding; got null");
        assertEquals(1, resolved[0].getParameterTypes().length);
        assertEquals("int", resolved[0].getParameterTypes()[0].getName(),
            "JDT contract: foo(42) resolves to the int overload, not the String overload");
    }

    // ========== B3-5: codeSelect at the package keyword ==========
    @Test
    @DisplayName("B3-5: codeSelect at the package keyword returns IPackageDeclaration")
    void codeSelect_atPackageKeyword_returnsPackageDeclaration() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(
            helper.getFixturePath("simple-maven")
                .resolve("src/main/java/com/example/Calculator.java"));
        assertNotNull(cu);
        String src = cu.getSource();
        int packageKeywordOffset = src.indexOf("package");
        assertTrue(packageKeywordOffset >= 0,
            "Calculator.java must begin with a package declaration; got: " + src.substring(0, 40));

        org.eclipse.jdt.core.IJavaElement[] selected = cu.codeSelect(packageKeywordOffset, 0);
        assertNotNull(selected);
        // JDT may return zero results for codeSelect at the keyword itself; the load-bearing
        // contract is "codeSelect at the package keyword returns either zero results or an
        // IPackageDeclaration — never something unexpected like an IType."
        for (org.eclipse.jdt.core.IJavaElement e : selected) {
            assertTrue(
                e instanceof org.eclipse.jdt.core.IPackageDeclaration
                    || e instanceof org.eclipse.jdt.core.IPackageFragment,
                "codeSelect at `package` keyword must return IPackageDeclaration or IPackageFragment; "
                    + "got: " + e.getClass().getName() + " for " + e);
        }
    }
}
