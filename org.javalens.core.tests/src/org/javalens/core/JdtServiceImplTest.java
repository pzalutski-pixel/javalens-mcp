package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdtServiceImpl.
 * Uses real JDT APIs with sample project fixtures.
 */
class JdtServiceImplTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        projectPath = helper.getFixturePath("simple-maven");
    }

    // ========== Project Loading Tests ==========

    @Test
    @DisplayName("loadProject should initialize Java project correctly")
    void loadProject_initializesJavaProject() {
        IJavaProject javaProject = service.getJavaProject();

        assertNotNull(javaProject, "Java project should be created");
        assertTrue(javaProject.exists(), "Java project should exist");
    }

    @Test
    @DisplayName("loadProject should detect source files (current fixture floor)")
    void loadProject_detectsSourceFiles() {
        int sourceFiles = service.getSourceFileCount();

        // The simple-maven fixture currently ships 45 .java files. Pinning a tighter floor
        // (>= 30) than the previous `>= 3` so a regression that drops most files to e.g.
        // 5 (e.g. linked-folder discovery missing a subdir) is caught instead of passing
        // silently. Not pinning the exact 45 because adding new fixtures is the
        // expected change pattern.
        assertTrue(sourceFiles >= 30,
            "Expected at least 30 source files in simple-maven fixture; got: " + sourceFiles);
    }

    @Test
    @DisplayName("loadProject should detect packages — known names present and total matches")
    void loadProject_detectsPackages() {
        int packages = service.getPackageCount();
        List<String> packageList = service.getPackages();

        // Pin every known package name. Previous test only verified ONE existed. If a
        // regression dropped half the packages from findPackages, the previous assertion
        // would still pass as long as com.example survived. Now each known package is
        // checked explicitly.
        assertTrue(packageList.contains("com.example"),
            "Expected com.example among packages; got: " + packageList);
        assertTrue(packageList.contains("com.example.service"),
            "Expected com.example.service among packages; got: " + packageList);
        assertTrue(packageList.contains("com.example.cycledemo.a"),
            "Expected com.example.cycledemo.a among packages; got: " + packageList);
        assertTrue(packageList.contains("com.example.cycledemo.b"),
            "Expected com.example.cycledemo.b among packages; got: " + packageList);

        // Sanity floor: getPackageCount must match the list size (defends against a stale
        // count field).
        assertEquals(packageList.size(), packages,
            "getPackageCount must equal getPackages().size()");
    }

    // ========== Compilation Unit Tests ==========

    @Test
    @DisplayName("getCompilationUnit should find existing Java files")
    void getCompilationUnit_findsExistingFiles() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");
        assertTrue(cu.exists(), "Compilation unit should exist");
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("getCompilationUnit should return null for non-existent files")
    void getCompilationUnit_returnsNullForMissing() {
        Path missingPath = projectPath.resolve("src/main/java/com/example/NotExists.java");

        ICompilationUnit cu = service.getCompilationUnit(missingPath);

        assertNull(cu, "Should return null for missing files");
    }

    @Test
    @DisplayName("getCompilationUnit should find files in subpackages")
    void getCompilationUnit_findsFilesInSubpackages() {
        Path servicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java");

        ICompilationUnit cu = service.getCompilationUnit(servicePath);

        assertNotNull(cu, "Should find UserService.java");
        assertEquals("UserService.java", cu.getElementName());
    }

    // ========== Element at Position Tests ==========

    @Test
    @DisplayName("getElementAtPosition should find class declaration")
    void getElementAtPosition_findsClass() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        // Position at 'Calculator' class name (line 5, column 13 - 0-based)
        // public class Calculator {
        //              ^
        IJavaElement element = service.getElementAtPosition(calculatorPath, 5, 13);

        assertNotNull(element, "Should find element at class declaration");
        assertTrue(element instanceof IType, "Should be a type: " + element.getClass().getName());
        assertEquals("Calculator", element.getElementName());
    }

    @Test
    @DisplayName("getElementAtPosition should find method declaration")
    void getElementAtPosition_findsMethod() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        // Position at 'add' method name. The previous "name.equals('add') OR 'a'"
        // tolerated either method or parameter — too permissive. Pin a precise position
        // (column 16) that lands ON the method name token, away from parameter names,
        // and require it to be an IMethod.
        IJavaElement element = service.getElementAtPosition(calculatorPath, 13, 16);

        assertNotNull(element, "Should find element at method declaration");
        assertTrue(element instanceof IMethod,
            "Expected IMethod at the 'add' method declaration; got "
                + element.getClass().getSimpleName() + " named " + element.getElementName());
        assertEquals("add", element.getElementName(),
            "Expected to find 'add' method; got: " + element.getElementName());
    }

    // ========== Offset Conversion Tests ==========

    @Test
    @DisplayName("getOffset should convert line/column to offset correctly")
    void getOffset_convertsCorrectly() throws Exception {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");

        // Line 0, Column 0 should be offset 0
        assertEquals(0, service.getOffset(cu, 0, 0), "First position should be offset 0");

        // Get source to verify
        String source = cu.getSource();
        assertNotNull(source, "Source should not be null");

        // Line 1 offset should be after first line
        int firstLineLength = source.indexOf('\n') + 1;
        assertEquals(firstLineLength, service.getOffset(cu, 1, 0),
            "Line 1 offset should equal first line length");
    }

    @Test
    @DisplayName("getLineNumber should convert offset to line correctly")
    void getLineNumber_convertsCorrectly() throws Exception {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");

        // Offset 0 should be line 0
        assertEquals(0, service.getLineNumber(cu, 0), "Offset 0 should be line 0");

        // Get source to find newline
        String source = cu.getSource();
        int firstNewline = source.indexOf('\n');

        // Offset right after first newline should be line 1
        assertEquals(1, service.getLineNumber(cu, firstNewline + 1),
            "After first newline should be line 1");
    }

    // ========== Type Resolution Tests ==========

    @Test
    @DisplayName("findType should find type by fully qualified name")
    void findType_findsByQualifiedName() {
        IType type = service.findType("com.example.Calculator");

        assertNotNull(type, "Should find Calculator by FQN");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("findType should find type by simple name")
    void findType_findsBySimpleName() {
        IType type = service.findType("Calculator");

        assertNotNull(type, "Should find Calculator by simple name");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("findType should return null for non-existent type")
    void findType_returnsNullForMissing() {
        IType type = service.findType("com.example.NotExists");

        assertNull(type, "Should return null for non-existent type");
    }

    // ========== All Java Files Tests ==========

    @Test
    @DisplayName("getAllJavaFiles should return all source files")
    void getAllJavaFiles_returnsAllFiles() {
        var files = service.getAllJavaFiles();

        // Tightened floor (was >= 3). simple-maven currently ships 45 .java files; >= 30
        // catches the "linked folder dropped most files" regression while leaving headroom
        // for expected fixture additions.
        assertTrue(files.size() >= 30,
            "Expected at least 30 Java files; got: " + files.size());

        // Verify expected files are present.
        boolean hasCalculator = files.stream().anyMatch(p -> p.toString().contains("Calculator.java"));
        boolean hasHelloWorld = files.stream().anyMatch(p -> p.toString().contains("HelloWorld.java"));
        boolean hasUserService = files.stream().anyMatch(p -> p.toString().contains("UserService.java"));

        assertTrue(hasCalculator, "Should include Calculator.java");
        assertTrue(hasHelloWorld, "Should include HelloWorld.java");
        assertTrue(hasUserService, "Should include UserService.java");

        // Sanity: getAllJavaFiles must agree with getSourceFileCount.
        assertEquals(service.getSourceFileCount(), files.size(),
            "getAllJavaFiles().size() must equal getSourceFileCount()");
    }

    // ========== PathUtils Tests ==========

    @Test
    @DisplayName("getPathUtils should return configured PathUtils")
    void getPathUtils_returnsConfigured() {
        IPathUtils pathUtils = service.getPathUtils();

        assertNotNull(pathUtils, "PathUtils should be configured");
    }

    @Test
    @DisplayName("getProjectRoot should return project path")
    void getProjectRoot_returnsPath() {
        Path root = service.getProjectRoot();

        assertNotNull(root, "Project root should be set");
        assertTrue(root.toString().contains("simple-maven"),
            "Should contain project name");
    }

    // ========== getTypeAtPosition (distinct from getElementAtPosition) ==========

    @Test
    @DisplayName("getTypeAtPosition returns the IType for a position inside a class")
    void getTypeAtPosition_findsEnclosingType() {
        Path calc = projectPath.resolve("src/main/java/com/example/Calculator.java");
        // Position inside the class body (anywhere — getTypeAtPosition walks ancestors).
        // Picking the class name itself for the simplest case.
        IType type = service.getTypeAtPosition(calc, 5, 13);
        assertNotNull(type, "getTypeAtPosition must surface the enclosing type");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("getTypeAtPosition returns enclosing type when position is inside a method body")
    void getTypeAtPosition_returnsEnclosingTypeForMethodPosition() {
        Path calc = projectPath.resolve("src/main/java/com/example/Calculator.java");
        // Inside add() — should walk up the ancestor chain and surface Calculator.
        IType type = service.getTypeAtPosition(calc, 13, 30);
        assertNotNull(type,
            "getTypeAtPosition must walk ancestors to surface the enclosing class");
        assertEquals("Calculator", type.getElementName(),
            "Even for a method-internal position, the enclosing type is Calculator");
    }

    // ========== findType — defensive branches + nested fallback ==========

    @Test
    @DisplayName("findType returns null for null input (defensive branch)")
    void findType_nullInput_returnsNull() {
        assertNull(service.findType(null),
            "findType(null) must return null per defensive null-guard, not throw");
    }

    @Test
    @DisplayName("findType returns null for blank input (defensive branch)")
    void findType_blankInput_returnsNull() {
        assertNull(service.findType(""),
            "findType('') must return null per defensive blank-guard, not throw");
        assertNull(service.findType("   "),
            "findType(whitespace) must return null per the same guard");
    }

    @Test
    @DisplayName("findType resolves nested type by dotted FQN (Outer.Inner)")
    void findType_nestedDottedFqn_resolves() {
        // TypeKindsFixture.Color is a nested enum. JDT's findType can return the OUTER
        // type for dotted nested input on first try, which the source's lastSeg-match
        // guard detects and falls back to the dollar-form. This test pins both the
        // dotted-input contract and the fallback's correctness.
        IType color = service.findType("com.example.TypeKindsFixture.Color");
        assertNotNull(color, "Expected to resolve nested type via dotted FQN");
        assertEquals("Color", color.getElementName());
    }

    // ========== Classpath + Loaded-at + Column ==========

    @Test
    @DisplayName("getClasspathEntryCount returns >0 for a loaded project")
    void getClasspathEntryCount_returnsPositive() {
        // simple-maven load produces at minimum a JRE container + at least one source folder.
        // Exact count varies with build-system inputs; pinning > 0 is enough to catch a
        // "raw classpath empty" regression. The configureJavaProject orchestrator's job
        // is to populate this.
        assertTrue(service.getClasspathEntryCount() > 0,
            "Expected at least one classpath entry after loadProject; got: "
                + service.getClasspathEntryCount());
    }

    @Test
    @DisplayName("getLoadedAt returns a non-null timestamp after load")
    void getLoadedAt_returnsTimestamp() {
        assertNotNull(service.getLoadedAt(),
            "getLoadedAt must be set during loadProject");
    }

    @Test
    @DisplayName("getColumnNumber/getLineNumber/getOffset round-trip for non-trivial positions")
    void offsetLineColumn_roundTrip() throws Exception {
        Path calc = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calc);
        assertNotNull(cu);

        // Pick a known position inside the file (line 5 = `public class Calculator {`).
        // Compute offset, then round-trip back through getLineNumber + getColumnNumber.
        int line = 5;
        int column = 13; // `Calculator` starts here on this line
        int offset = service.getOffset(cu, line, column);
        assertEquals(line, service.getLineNumber(cu, offset),
            "Line round-trip must preserve the input line");
        assertEquals(column, service.getColumnNumber(cu, offset),
            "Column round-trip must preserve the input column");
    }

    @Test
    @DisplayName("getContextLine returns the trimmed line text at the given offset")
    void getContextLine_returnsTrimmedLine() throws Exception {
        Path calc = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calc);
        assertNotNull(cu);

        // Offset for line 5 column 13 → "public class Calculator {"
        int offset = service.getOffset(cu, 5, 13);
        String contextLine = service.getContextLine(cu, offset);
        assertNotNull(contextLine);
        assertFalse(contextLine.isBlank(),
            "Context line must not be blank at a code-bearing offset");
        assertTrue(contextLine.contains("Calculator"),
            "Context line at the class declaration must mention 'Calculator'; got: "
                + contextLine);
        // Source trims the line — leading whitespace should be gone.
        assertEquals(contextLine.trim(), contextLine,
            "Context line must already be trimmed");
    }

    // ========== Time limit ==========

    @Test
    @DisplayName("with JAVALENS_TIMEOUT_SECONDS unset, as the build leaves it, no call is limited: no limit, no deadline, no warning")
    void timeLimit_unset_noLimit() {
        service.setDeadlines(seconds -> Deadline.atCheck(seconds, 0));
        assertAll(
            () -> assertNull(service.getTimeoutSeconds()),
            () -> assertNull(service.newDeadline(), "no deadline, whatever deadlines would be made"),
            () -> assertEquals(List.of(), service.getWarnings()));
    }

    @Test
    @DisplayName("a limit of 30 starts each call's deadline at 30 seconds, through the deadlines seam when one is set")
    void timeLimit_set_startsDeadlines() {
        service.setTimeoutSetting(TimeoutSetting.of("30"));
        Deadline clockDeadline = service.newDeadline();
        service.setDeadlines(seconds -> Deadline.atCheck(seconds, 0));
        Deadline seamDeadline = service.newDeadline();
        assertAll(
            () -> assertEquals(30, service.getTimeoutSeconds()),
            () -> assertEquals(30, clockDeadline.seconds()),
            () -> assertNotSame(clockDeadline, service.newDeadline(), "each call starts its own deadline"),
            () -> assertEquals(30, seamDeadline.seconds()),
            () -> assertTrue(seamDeadline.expired(), "the seam's deadline"),
            () -> assertEquals(List.of(), service.getWarnings()));
    }

    @Test
    @DisplayName("a value that is not a whole number sets no limit and raises exactly TIMEOUT_SECONDS_INVALID; a clamped one limits calls and raises it")
    void timeLimit_invalidValues_warn() {
        service.setDeadlines(seconds -> Deadline.atCheck(seconds, 0));
        service.setTimeoutSetting(TimeoutSetting.of("abc"));
        Integer notANumberLimit = service.getTimeoutSeconds();
        Deadline notANumberDeadline = service.newDeadline();
        List<LoadWarning> notANumberWarnings = service.getWarnings();
        service.setTimeoutSetting(TimeoutSetting.of("2"));
        assertAll(
            () -> assertNull(notANumberLimit),
            () -> assertNull(notANumberDeadline),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.TIMEOUT_SECONDS_INVALID,
                    "JAVALENS_TIMEOUT_SECONDS is \"abc\", which is not a whole number of seconds: tool calls have no time limit",
                    TIMEOUT_REMEDIATION)),
                notANumberWarnings),
            () -> assertEquals(5, service.getTimeoutSeconds()),
            () -> assertEquals(5, service.newDeadline().seconds()),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.TIMEOUT_SECONDS_INVALID,
                    "JAVALENS_TIMEOUT_SECONDS is \"2\", below 5: each tool call is limited to 5 seconds",
                    TIMEOUT_REMEDIATION)),
                service.getWarnings()));
    }

    private static final String TIMEOUT_REMEDIATION =
        "Set JAVALENS_TIMEOUT_SECONDS to a whole number of seconds from 5 to 300, or unset it for no limit, "
            + "then restart JavaLens.";

    // ========== Defensive null-javaProject guards (pre-loadProject state) ==========

    @Test
    @DisplayName("getCompilationUnit on a fresh (un-loaded) service returns null instead of throwing")
    void getCompilationUnit_nullJavaProject_returnsNull() {
        JdtServiceImpl fresh = new JdtServiceImpl();
        // No loadProject — javaProject field is still null. The defensive guard must
        // fire and return null rather than NPE.
        Path anyPath = projectPath.resolve("src/main/java/com/example/Calculator.java");
        assertNull(fresh.getCompilationUnit(anyPath),
            "Pre-load getCompilationUnit must return null per the early-return guard");
    }

    @Test
    @DisplayName("getClasspathEntryCount on a fresh (un-loaded) service returns 0 (defensive guard)")
    void getClasspathEntryCount_nullJavaProject_returnsZero() {
        JdtServiceImpl fresh = new JdtServiceImpl();
        assertEquals(0, fresh.getClasspathEntryCount(),
            "Pre-load getClasspathEntryCount must return 0, not throw NPE");
    }

    @Test
    @DisplayName("getAllJavaFiles on a fresh (un-loaded) service returns an empty list")
    void getAllJavaFiles_nullJavaProject_returnsEmpty() {
        JdtServiceImpl fresh = new JdtServiceImpl();
        var files = fresh.getAllJavaFiles();
        assertNotNull(files,
            "getAllJavaFiles must never return null (guard returns empty list)");
        assertTrue(files.isEmpty(),
            "Pre-load getAllJavaFiles must return [] per the early-return guard");
    }

    // ========== getTypeAtPosition: position outside any type ==========

    @Test
    @DisplayName("getTypeAtPosition at the package declaration line returns null (or empty type-less element)")
    void getTypeAtPosition_packageLine_handledGracefully() {
        // Line 0 col 0 of Calculator.java is the `package` keyword. The element at that
        // position (if anything resolves) is the package declaration — not an IType and
        // has no IType ancestor. getTypeAtPosition must therefore return null.
        Path calc = projectPath.resolve("src/main/java/com/example/Calculator.java");
        IType type = service.getTypeAtPosition(calc, 0, 0);
        // Either null (no element at offset) or null (element is package-level with no
        // type ancestor) — both branches of getTypeAtPosition must NOT crash and must
        // return null for a position outside any class body.
        assertNull(type,
            "getTypeAtPosition at the package line must yield null; got: " + type);
    }
}
