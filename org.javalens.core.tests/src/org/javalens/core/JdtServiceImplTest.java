package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

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
    @DisplayName("loadProject should detect source files")
    void loadProject_detectsSourceFiles() {
        int sourceFiles = service.getSourceFileCount();

        assertTrue(sourceFiles >= 3, "Should find at least 3 source files (Calculator, HelloWorld, UserService)");
    }

    @Test
    @DisplayName("loadProject should detect packages")
    void loadProject_detectsPackages() {
        int packages = service.getPackageCount();

        assertTrue(packages >= 1, "Should find at least 1 package");
        assertTrue(service.getPackages().contains("com.example") ||
                   service.getPackages().stream().anyMatch(p -> p.startsWith("com.example")),
                   "Should find com.example package");
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

        // Position at 'add' method name (line 13, column 15 - 0-based)
        // public int add(int a, int b) {
        //            ^
        IJavaElement element = service.getElementAtPosition(calculatorPath, 13, 15);

        assertNotNull(element, "Should find element at method declaration");
        // Element could be method or parameter depending on exact position
        String name = element.getElementName();
        assertTrue(name.equals("add") || name.equals("a"),
            "Should find add method or its parameter, got: " + name);
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

        assertFalse(files.isEmpty(), "Should find Java files");
        assertTrue(files.size() >= 3, "Should find at least 3 files");

        // Verify file names
        boolean hasCalculator = files.stream().anyMatch(p -> p.toString().contains("Calculator.java"));
        boolean hasHelloWorld = files.stream().anyMatch(p -> p.toString().contains("HelloWorld.java"));
        boolean hasUserService = files.stream().anyMatch(p -> p.toString().contains("UserService.java"));

        assertTrue(hasCalculator, "Should include Calculator.java");
        assertTrue(hasHelloWorld, "Should include HelloWorld.java");
        assertTrue(hasUserService, "Should include UserService.java");
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
}
