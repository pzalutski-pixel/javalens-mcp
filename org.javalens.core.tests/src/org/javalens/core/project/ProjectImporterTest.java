package org.javalens.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProjectImporter.
 * Tests build system detection, source file counting, and classpath configuration.
 */
class ProjectImporterTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProjectImporter importer;
    private WorkspaceManager workspaceManager;
    private Path mavenFixturePath;

    @BeforeEach
    void setUp() throws Exception {
        importer = new ProjectImporter();
        workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
        mavenFixturePath = helper.getFixturePath("simple-maven");
    }

    // ========== Build System Detection Tests ==========

    @Test
    @DisplayName("detectBuildSystem should detect Maven project")
    void detectBuildSystem_detectsMaven() {
        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(mavenFixturePath);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Should detect Maven project from pom.xml");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Gradle project with build.gradle")
    void detectBuildSystem_detectsGradle(@TempDir Path tempDir) throws IOException {
        // Create a fake Gradle project
        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle build file");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.GRADLE, buildSystem,
            "Should detect Gradle project from build.gradle");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Gradle project with build.gradle.kts")
    void detectBuildSystem_detectsGradleKts(@TempDir Path tempDir) throws IOException {
        // Create a fake Kotlin Gradle project
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// Kotlin Gradle build file");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.GRADLE, buildSystem,
            "Should detect Gradle project from build.gradle.kts");
    }

    @Test
    @DisplayName("detectBuildSystem should return UNKNOWN for plain project")
    void detectBuildSystem_returnsUnknownForPlainProject(@TempDir Path tempDir) throws IOException {
        // Create a plain Java project with no build file
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.UNKNOWN, buildSystem,
            "Should return UNKNOWN for project without build file");
    }

    @Test
    @DisplayName("detectBuildSystem should prefer Maven over Gradle when both exist")
    void detectBuildSystem_prefersMavenOverGradle(@TempDir Path tempDir) throws IOException {
        // Create both build files
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Should prefer Maven when both pom.xml and build.gradle exist");
    }

    // ========== Source File Counting Tests ==========

    @Test
    @DisplayName("countSourceFiles should count Java files in Maven layout")
    void countSourceFiles_countsJavaFiles() {
        int count = importer.countSourceFiles(mavenFixturePath);

        assertTrue(count >= 3,
            "Should find at least 3 Java files (Calculator, HelloWorld, UserService): " + count);
    }

    @Test
    @DisplayName("countSourceFiles should return 0 for empty project")
    void countSourceFiles_returnsZeroForEmpty(@TempDir Path tempDir) {
        int count = importer.countSourceFiles(tempDir);

        assertEquals(0, count, "Should return 0 for project with no Java files");
    }

    @Test
    @DisplayName("countSourceFiles should count files in src layout")
    void countSourceFiles_countsSrcLayout(@TempDir Path tempDir) throws IOException {
        // Create simple src layout
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");
        Files.writeString(srcDir.resolve("Helper.java"), "public class Helper {}");

        int count = importer.countSourceFiles(tempDir);

        assertEquals(2, count, "Should find 2 Java files in src directory");
    }

    // ========== Package Finding Tests ==========

    @Test
    @DisplayName("findPackages should find all packages in project")
    void findPackages_findsAllPackages() {
        List<String> packages = importer.findPackages(mavenFixturePath);

        assertFalse(packages.isEmpty(), "Should find at least one package");
        assertTrue(packages.stream().anyMatch(p -> p.contains("com.example")),
            "Should find com.example package");
    }

    @Test
    @DisplayName("findPackages should find nested packages")
    void findPackages_findsNestedPackages() {
        List<String> packages = importer.findPackages(mavenFixturePath);

        // Should find both com.example and com.example.service
        assertTrue(packages.stream().anyMatch(p -> p.contains("service")),
            "Should find service subpackage");
    }

    @Test
    @DisplayName("findPackages should return empty for no packages")
    void findPackages_returnsEmptyForNoPackages(@TempDir Path tempDir) {
        List<String> packages = importer.findPackages(tempDir);

        assertTrue(packages.isEmpty(), "Should return empty list for empty project");
    }

    // ========== Java Project Configuration Tests ==========

    @Test
    @DisplayName("configureJavaProject should add JRE container to classpath")
    void configureJavaProject_addsJreContainer() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("jre-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        IClasspathEntry[] classpath = javaProject.getRawClasspath();
        boolean hasJre = Arrays.stream(classpath)
            .anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                && entry.getPath().toString().contains("JRE"));

        assertTrue(hasJre, "Classpath should contain JRE container");
    }

    @Test
    @DisplayName("configureJavaProject should add source folders")
    void configureJavaProject_addsSourceFolders() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("src-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        IClasspathEntry[] classpath = javaProject.getRawClasspath();
        boolean hasSource = Arrays.stream(classpath)
            .anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE);

        assertTrue(hasSource, "Classpath should contain source entries");
    }

    @Test
    @DisplayName("configureJavaProject should set output location")
    void configureJavaProject_setsOutputLocation() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("output-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        assertNotNull(javaProject.getOutputLocation(), "Output location should be set");
        assertTrue(javaProject.getOutputLocation().toString().contains("bin"),
            "Output location should be bin folder");
    }

    @Test
    @DisplayName("configureJavaProject should return valid Java project")
    void configureJavaProject_returnsValidProject() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("valid-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        assertNotNull(javaProject, "Should return a Java project");
        assertTrue(javaProject.exists(), "Java project should exist");
        assertEquals(project, javaProject.getProject(), "Should wrap the same IProject");
    }
}
